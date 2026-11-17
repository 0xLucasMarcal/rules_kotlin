/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package io.bazel.kotlin.builder.tasks.jvm

import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Runs KSP2 for a target against the state the target's previous request left, so that KSP only
 * processes the sources that changed and the sources that looked up a classpath class that changed.
 *
 * The state of a target holds the staged sources, KSP's caches and outputs, and the inputs of the
 * last request that completed. A request compares its inputs with those: the sources whose digest
 * changed are modified, the sources that are gone are removed, and the classes whose entry changed
 * in a library jar whose digest changed are changed classes. A change KSP cannot process
 * incrementally, such as another processor classpath, option or set of libraries, discards the
 * state, and KSP processes every source.
 *
 * KSP updates its outputs in place, so a request that fails or does not complete would leave them
 * half updated: a request marks the state in progress until it completes, and the next request
 * discards a state that is still marked.
 */
class Ksp2IncrementalProcessing(
  private val root: Path,
  val options: Ksp2IncrementalOptions,
) {
  /** What one KSP2 request processes. */
  class Request(
    val moduleName: String,
    val sources: List<String>,
    val sourceJars: List<String>,
    val libraries: List<String>,
    /** The output jars, the first of which tells the configurations of a target apart. */
    val outputs: List<String>,
    /** The settings a change of which KSP cannot process incrementally. */
    val configuration: List<String>,
    val inputDigests: Map<String, String>,
  )

  /** Where KSP reads the sources from and writes its caches and outputs to. */
  class Directories(
    val base: Path,
    val sourceRoots: List<File>,
    val javaSourceRoots: List<File>,
    val kotlinOutput: Path,
    val javaOutput: Path,
    val classOutput: Path,
    val resourceOutput: Path,
    val caches: Path,
  )

  /** What changed since the previous request; KSP processes every source when [full]. */
  class Changes(
    val full: Boolean,
    val modified: List<File>,
    val removed: List<File>,
    val changedClasses: List<String>,
  )

  /** A staged source: its digest and the source jar it comes from, empty for a plain source. */
  private data class Staged(
    val digest: String,
    val origin: String,
  )

  /** The inputs of the last request of a target that completed. */
  private class Inputs(
    val fingerprint: String,
    val sources: Map<String, Staged>,
    val sourceJars: Map<String, String>,
    val libraries: Map<String, String>,
  )

  private class TargetState(
    val dir: Path,
  ) {
    val lock: Path = dir.resolve(".lock")
    val inProgress: Path = dir.resolve("in-progress")
    val inputs: Path = dir.resolve("inputs")

    /** The class entries of each library, by a hash of the library's path. */
    val libraryClasses: Path = dir.resolve("library-classes")
    val base: Path = dir.resolve("work")
    val sources: Path = base.resolve("srcs")
    val kotlinOutput: Path = base.resolve("kotlin")
    val javaOutput: Path = base.resolve("java")
    val classOutput: Path = base.resolve("classes")
    val resourceOutput: Path = base.resolve("resources")
    val caches: Path = base.resolve("caches")

    /** Copies of the last output jars, with what they were created from, see [packageOutputs]. */
    val jars: Path = dir.resolve("jars")
    val jarsKey: Path = jars.resolve("key")
  }

  /** Thrown when an incremental request finds it must process every source after all. */
  private class FullProcessingRequired(
    val reason: String,
  ) : Exception(reason)

  private class Timer {
    private val started = System.nanoTime()
    private val phases = LinkedHashMap<String, Long>()

    fun <T> time(
      phase: String,
      block: () -> T,
    ): T {
      val start = System.nanoTime()
      try {
        return block()
      } finally {
        phases.merge(phase, System.nanoTime() - start, Long::plus)
      }
    }

    fun line(): String =
      buildString {
        phases.forEach { (phase, nanos) ->
          append(", ")
            .append(phase)
            .append(' ')
            .append(millis(nanos))
            .append(" ms")
        }
        append(", total ").append(millis(System.nanoTime() - started)).append(" ms")
      }

    private fun millis(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos)
  }

  /**
   * Processes the [request] incrementally, or returns null when the target's state is locked by
   * another request, which the caller then processes without state.
   *
   * @param invoke runs KSP, incrementally unless the changes are null, and returns its exit code.
   * @param packageOutputs creates the output jars from KSP's output directories.
   */
  fun process(
    request: Request,
    out: PrintStream,
    invoke: (Directories, Changes?) -> Int,
    packageOutputs: (Directories) -> Unit,
  ): Int? {
    // The real path: KSP relates the paths of the sources it parses to the base directory.
    val state =
      TargetState(Files.createDirectories(root.resolve(targetKey(request))).toRealPath())
    FileChannel
      .open(state.lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      .use { channel ->
        val lock =
          try {
            channel.tryLock()
          } catch (_: OverlappingFileLockException) {
            null
          } ?: return null
        try {
          return processWithState(request, state, out, invoke, packageOutputs)
        } finally {
          lock.release()
        }
      }
  }

  private fun processWithState(
    request: Request,
    state: TargetState,
    out: PrintStream,
    invoke: (Directories, Changes?) -> Int,
    packageOutputs: (Directories) -> Unit,
  ): Int {
    val timer = Timer()
    val fingerprint = fingerprint(request)
    var reason: String? = null
    val previous =
      when {
        Files.exists(state.inProgress) -> {
          reason = "the previous request did not complete"
          null
        }

        !Files.exists(state.inputs) -> {
          reason = "no state"
          null
        }

        else -> {
          readInputs(state)
            .also {
              when {
                it == null -> reason = "the state is unreadable"
                it.fingerprint != fingerprint -> reason = "the configuration changed"
              }
            }?.takeIf { it.fingerprint == fingerprint }
        }
      }
    var completed = false
    try {
      val staging =
        try {
          timer.time("snapshot") { prepare(request, state, previous, fingerprint) }
        } catch (e: FullProcessingRequired) {
          reason = e.reason
          timer.time("snapshot") { prepare(request, state, null, fingerprint) }
        }
      val directories = staging.directories
      val changes = staging.changes
      val code = timer.time("ksp") { invoke(directories, changes) }
      if (code != 0) {
        return code
      }
      val mismatches =
        if (options.verify && !changes.full) {
          timer.time("verify") { verify(state, directories, invoke) }
        } else {
          null
        }
      val jarsReused =
        timer.time("outputs") { packageOutputs(request, state, directories, packageOutputs) }
      timer.time("commit") {
        staging.commit()
        Files.delete(state.inProgress)
      }
      completed = true
      if (options.summary || !mismatches.isNullOrEmpty()) {
        out.println(
          buildString {
            append("ksp2 incremental processing: ")
            append(if (changes.full) "full" else "incremental")
            reason?.let { append(" (").append(it).append(')') }
            if (!changes.full) {
              append(", ").append(changes.modified.size).append('/')
              append(staging.sourceCount).append(" sources modified, ")
              append(changes.removed.size).append(" removed, ")
              append(changes.changedClasses.size).append(" changed classes in ")
              append(staging.changedLibraries).append(" libraries")
            } else {
              append(", ").append(staging.sourceCount).append(" sources")
            }
            if (jarsReused) {
              append(", jars reused")
            }
            mismatches?.let {
              if (it.isEmpty()) {
                append(", verified")
              } else {
                append(
                  ", MISMATCH with processing from scratch: ",
                ).append(it.size).append(" outputs ")
                append(it.take(MISMATCHES_SHOWN).joinToString(prefix = "[", postfix = "]"))
              }
            }
            append(timer.line())
          },
        )
      }
      return 0
    } finally {
      if (!completed) {
        discard(state)
      }
    }
  }

  /**
   * Processes the staged sources from scratch into directories of their own and returns the paths
   * of the outputs that differ from those of the incremental processing into [directories].
   */
  private fun verify(
    state: TargetState,
    directories: Directories,
    invoke: (Directories, Changes?) -> Int,
  ): List<String> {
    val base = state.dir.resolve("verify")
    deleteRecursively(base)
    try {
      val scratch =
        Directories(
          base = base,
          sourceRoots = directories.sourceRoots,
          javaSourceRoots = directories.javaSourceRoots,
          kotlinOutput = base.resolve("kotlin"),
          javaOutput = base.resolve("java"),
          classOutput = base.resolve("classes"),
          resourceOutput = base.resolve("resources"),
          caches = base.resolve("caches"),
        )
      listOf(
        scratch.kotlinOutput,
        scratch.javaOutput,
        scratch.classOutput,
        scratch.resourceOutput,
        scratch.caches,
      ).forEach { Files.createDirectories(it) }
      val code = invoke(scratch, null)
      if (code != 0) {
        return listOf("processing from scratch failed with exit code $code")
      }
      val incremental = outputDigests(directories)
      val fromScratch = outputDigests(scratch)
      return (incremental.keys + fromScratch.keys)
        .filter { incremental[it] != fromScratch[it] }
        .sorted()
    } finally {
      deleteRecursively(base)
    }
  }

  /** The digests of the files in KSP's output directories, by output directory and path. */
  private fun outputDigests(directories: Directories): Map<String, String> {
    val digests = HashMap<String, String>()
    listOf(
      "kotlin" to directories.kotlinOutput,
      "java" to directories.javaOutput,
      "classes" to directories.classOutput,
      "resources" to directories.resourceOutput,
    ).forEach { (name, directory) ->
      if (Files.isDirectory(directory)) {
        Files.walk(directory).use { paths ->
          paths.filter { Files.isRegularFile(it) }.forEach {
            digests["$name/${directory.relativize(it)}"] = sha256(Files.readAllBytes(it))
          }
        }
      }
    }
    return digests
  }

  /** What [prepare] staged, and how to record it once KSP completes. */
  private class Staging(
    val directories: Directories,
    val changes: Changes,
    val sourceCount: Int,
    val changedLibraries: Int,
    val commit: () -> Unit,
  )

  /**
   * Stages the sources and snapshots the libraries of the [request] against the [previous] inputs,
   * or from scratch when there are none.
   */
  private fun prepare(
    request: Request,
    state: TargetState,
    previous: Inputs?,
    fingerprint: String,
  ): Staging {
    if (previous == null) {
      deleteRecursively(state.base)
      deleteRecursively(state.libraryClasses)
    } else {
      // KSP restores the outputs of the sources it does not process again from the backups in its
      // caches, so the outputs of a removed source, or those a processed source no longer
      // generates, must be gone when it starts.
      listOf(state.kotlinOutput, state.javaOutput, state.classOutput, state.resourceOutput)
        .forEach(::deleteRecursively)
    }
    Files.deleteIfExists(state.inputs)
    Files.createDirectories(state.dir)
    Files.write(state.inProgress, ByteArray(0))
    listOf(
      state.sources,
      state.kotlinOutput,
      state.javaOutput,
      state.classOutput,
      state.resourceOutput,
      state.caches,
      state.libraryClasses,
    ).forEach { Files.createDirectories(it) }

    val sources = LinkedHashMap<String, Staged>()
    val modified = TreeSet<String>()

    fun stage(
      path: String,
      staged: Staged,
      write: (Path) -> Unit,
    ) {
      val relative = stagedPath(path)
      if (sources.put(relative, staged) != null && previous != null) {
        throw FullProcessingRequired("two sources stage to $relative")
      }
      val target = state.sources.resolve(relative)
      if (previous?.sources?.get(relative)?.digest == staged.digest &&
        Files.isRegularFile(target)
      ) {
        return
      }
      Files.createDirectories(target.parent)
      write(target)
      modified.add(relative)
    }

    for (source in request.sources) {
      val digest = request.inputDigests[source] ?: sha256(Files.readAllBytes(Paths.get(source)))
      stage(source, Staged(digest, "")) {
        Files.copy(Paths.get(source), it, StandardCopyOption.REPLACE_EXISTING)
      }
    }

    val sourceJars = LinkedHashMap<String, String>()
    for (sourceJar in request.sourceJars) {
      val digest =
        request.inputDigests[sourceJar] ?: sha256(Files.readAllBytes(Paths.get(sourceJar)))
      sourceJars[sourceJar] = digest
      val unchanged =
        previous
          ?.takeIf { it.sourceJars[sourceJar] == digest }
          ?.sources
          ?.filterValues { it.origin == sourceJar }
          ?.takeIf { entries ->
            entries.keys.all { Files.isRegularFile(state.sources.resolve(it)) }
          }
      if (unchanged != null) {
        unchanged.forEach { (relative, staged) -> stage(relative, staged) {} }
        continue
      }
      ZipFile(sourceJar).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
          val bytes = zip.getInputStream(entry).use { it.readBytes() }
          stage(entry.name, Staged(sha256(bytes), sourceJar)) { Files.write(it, bytes) }
        }
      }
    }

    val removed =
      previous
        ?.sources
        ?.keys
        ?.filterNot { it in sources }
        .orEmpty()
        .sorted()
    for (relative in removed) {
      Files.deleteIfExists(state.sources.resolve(relative))
    }

    val libraries = LinkedHashMap<String, String>()
    for (library in request.libraries) {
      libraries[library] =
        request.inputDigests[library] ?: sha256(Files.readAllBytes(Paths.get(library)))
    }
    val changedLibraries =
      libraries.filter { (library, digest) -> previous?.libraries?.get(library) != digest }
    val changedClasses = TreeSet<String>()
    val tables = LinkedHashMap<String, Map<String, String>>()
    for (library in changedLibraries.keys) {
      // A library that is not a jar has no table: a change to it processes every source.
      val current = classEntries(library)
      if (current == null) {
        if (previous != null) {
          throw FullProcessingRequired("$library is not a readable jar")
        }
        continue
      }
      tables[library] = current
      if (previous != null) {
        val before =
          readClassEntries(state, library, previous.libraries.getValue(library))
            ?: throw FullProcessingRequired("no class entries of $library")
        (before.keys + current.keys)
          .filter { before[it] != current[it] }
          .mapNotNullTo(changedClasses, ::className)
      }
    }

    val roots = LinkedHashSet<File>()
    val javaRoots = LinkedHashSet<File>()
    for (relative in sources.keys) {
      val parent =
        state.sources
          .resolve(relative)
          .parent
          .toFile()
      roots.add(parent)
      if (relative.endsWith(".java")) {
        javaRoots.add(parent)
      }
    }
    if (roots.isEmpty()) {
      roots.add(state.sources.toFile())
    }

    fun absolute(relative: String) =
      state.sources
        .resolve(relative)
        .toAbsolutePath()
        .toFile()
    return Staging(
      directories =
        Directories(
          base = state.base,
          sourceRoots = roots.toList(),
          javaSourceRoots = javaRoots.toList(),
          kotlinOutput = state.kotlinOutput,
          javaOutput = state.javaOutput,
          classOutput = state.classOutput,
          resourceOutput = state.resourceOutput,
          caches = state.caches,
        ),
      changes =
        Changes(
          full = previous == null,
          modified = if (previous == null) emptyList() else modified.map(::absolute),
          removed = removed.map(::absolute),
          changedClasses = changedClasses.toList(),
        ),
      sourceCount = sources.size,
      changedLibraries = if (previous == null) 0 else changedLibraries.size,
      commit = {
        tables.forEach { (library, entries) ->
          writeClassEntries(state, library, libraries.getValue(library), entries)
        }
        writeInputs(state, Inputs(fingerprint, sources, sourceJars, libraries))
      },
    )
  }

  /**
   * Creates the output jars, or copies those of the previous request when KSP left its output
   * directories as they were then, which is when a request processes no source or regenerates
   * nothing. Returns whether the jars are copies.
   */
  private fun packageOutputs(
    request: Request,
    state: TargetState,
    directories: Directories,
    packageOutputs: (Directories) -> Unit,
  ): Boolean {
    val outputs =
      request.outputs.mapIndexed { index, output ->
        Paths.get(output) to
          state.jars.resolve("$index.jar")
      }
    val key = outputsKey(request, directories)
    val cachedKey =
      try {
        Files.readAllLines(state.jarsKey, StandardCharsets.UTF_8).firstOrNull()
      } catch (_: IOException) {
        null
      }
    if (key == cachedKey && outputs.all { (_, jar) -> Files.isRegularFile(jar) }) {
      outputs.forEach { (output, jar) ->
        Files.copy(jar, output, StandardCopyOption.REPLACE_EXISTING)
      }
      return true
    }
    packageOutputs(directories)
    Files.deleteIfExists(state.jarsKey)
    // The key last: copies that do not complete are never taken for the jars of the key.
    Files.createDirectories(state.jars)
    outputs.forEach { (output, jar) ->
      Files.copy(output, jar, StandardCopyOption.REPLACE_EXISTING)
    }
    writeAtomically(state.jarsKey, listOf(key))
    return false
  }

  /**
   * What the output jars are created from: the files of KSP's output directories, each by its size
   * and modification time. KSP restores the outputs it does not regenerate from its backups with
   * their modification times, and writes those it regenerates anew. The times are in milliseconds:
   * a copy keeps less than the nanoseconds of the original on some platforms, e.g. macOS.
   */
  private fun outputsKey(
    request: Request,
    directories: Directories,
  ): String {
    val lines = ArrayList<String>()
    lines.add(FORMAT)
    lines.add(request.outputs.size.toString())
    val outputDirectories =
      listOf(
        directories.kotlinOutput,
        directories.javaOutput,
        directories.classOutput,
        directories.resourceOutput,
      )
    for ((index, directory) in outputDirectories.withIndex()) {
      if (!Files.isDirectory(directory)) {
        continue
      }
      Files.walk(directory).use { paths ->
        for (path in paths) {
          val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
          val relative = directory.relativize(path).toString()
          if (attributes.isDirectory) {
            lines.add("$index dir $relative")
          } else {
            lines.add(
              "$index file $relative ${attributes.size()} " +
                attributes.lastModifiedTime().to(TimeUnit.MILLISECONDS),
            )
          }
        }
      }
    }
    lines.sort()
    return sha256(lines.joinToString("\n").toByteArray(StandardCharsets.UTF_8))
  }

  /**
   * The class entries of the jar at [library], with their CRC and size, by entry name, or null when
   * it is not a readable jar.
   */
  private fun classEntries(library: String): Map<String, String>? =
    try {
      ZipFile(library).use { zip ->
        zip
          .entries()
          .asSequence()
          .filter { !it.isDirectory && it.name.endsWith(CLASS_SUFFIX) }
          .associate { it.name to "${it.crc}:${it.size}" }
      }
    } catch (_: IOException) {
      null
    }

  private fun classEntriesFile(
    state: TargetState,
    library: String,
  ): Path =
    state.libraryClasses.resolve(sha256(library.toByteArray(StandardCharsets.UTF_8)).take(32))

  private fun readClassEntries(
    state: TargetState,
    library: String,
    digest: String,
  ): Map<String, String>? {
    val lines =
      try {
        Files.readAllLines(classEntriesFile(state, library), StandardCharsets.UTF_8)
      } catch (_: IOException) {
        return null
      }
    if (lines.firstOrNull() != "$library\t$digest") {
      return null
    }
    return lines.drop(1).associate { it.substringBefore('\t') to it.substringAfter('\t') }
  }

  private fun writeClassEntries(
    state: TargetState,
    library: String,
    digest: String,
    entries: Map<String, String>,
  ) {
    val lines = listOf("$library\t$digest") + entries.map { (name, entry) -> "$name\t$entry" }
    writeAtomically(classEntriesFile(state, library), lines)
  }

  private fun readInputs(state: TargetState): Inputs? {
    val lines =
      try {
        Files.readAllLines(state.inputs, StandardCharsets.UTF_8)
      } catch (_: IOException) {
        return null
      }
    var fingerprint: String? = null
    val sources = LinkedHashMap<String, Staged>()
    val sourceJars = LinkedHashMap<String, String>()
    val libraries = LinkedHashMap<String, String>()
    for (line in lines) {
      val fields = line.split('\t')
      when (fields[0]) {
        "fingerprint" -> fingerprint = fields[1]
        "source" -> sources[fields[1]] = Staged(fields[2], fields[3])
        "srcjar" -> sourceJars[fields[1]] = fields[2]
        "library" -> libraries[fields[1]] = fields[2]
        else -> return null
      }
    }
    return Inputs(fingerprint ?: return null, sources, sourceJars, libraries)
  }

  private fun writeInputs(
    state: TargetState,
    inputs: Inputs,
  ) {
    val lines = ArrayList<String>()
    lines.add("fingerprint\t${inputs.fingerprint}")
    inputs.sources.forEach { (path, staged) ->
      lines.add("source\t$path\t${staged.digest}\t${staged.origin}")
    }
    inputs.sourceJars.forEach { (path, digest) -> lines.add("srcjar\t$path\t$digest") }
    inputs.libraries.forEach { (path, digest) -> lines.add("library\t$path\t$digest") }
    writeAtomically(state.inputs, lines)
  }

  private fun discard(state: TargetState) {
    runCatching {
      Files.deleteIfExists(state.inputs)
      deleteRecursively(state.base)
      deleteRecursively(state.libraryClasses)
      deleteRecursively(state.jars)
      Files.deleteIfExists(state.inProgress)
    }
  }

  private fun fingerprint(request: Request): String =
    sha256(
      (
        listOf(FORMAT, request.moduleName) +
          request.configuration +
          listOf("libraries") +
          request.libraries
      ).joinToString("\n").toByteArray(StandardCharsets.UTF_8),
    )

  /** The state directory name of the request's target, which tells its configurations apart. */
  private fun targetKey(request: Request): String {
    val readable =
      request.moduleName
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_')
        .takeLast(80)
    return "$readable-" +
      sha256(
        "${request.moduleName}\n${request.outputs.first()}".toByteArray(StandardCharsets.UTF_8),
      ).take(16)
  }

  companion object {
    /** The version of the state's format; another version discards the state. */
    private const val FORMAT = "ksp2-incremental-1"
    private const val CLASS_SUFFIX = ".class"
    private const val MISMATCHES_SHOWN = 5

    /** The staged directory of the sources outside the execroot, see [stagedPath]. */
    private const val OUTSIDE = "_outside"
    private val MULTI_RELEASE_PREFIX = Regex("^META-INF/versions/\\d+/")

    val DEFAULT_ROOT: Path = Paths.get("bazel-out", "_ksp2_ic").toAbsolutePath()

    /**
     * Where a source at [path] is staged, relative to the staged sources: an execroot-relative path
     * stays as it is, and a path that is absolute or leaves its root stays under the staged sources.
     */
    fun stagedPath(path: String): String {
      val normalized = Paths.get(path).normalize()
      if (!normalized.isAbsolute && !normalized.startsWith("..")) {
        return normalized.toString()
      }
      val segments = normalized.map { if (it.toString() == "..") "__" else it.toString() }
      return (listOf(OUTSIDE) + segments).joinToString("/")
    }

    /**
     * The name KSP looks the class of the jar [entry] up by: its binary name with nested classes
     * separated by dots, as the KSP Gradle plugin passes changed classes.
     */
    fun className(entry: String): String? {
      if (!entry.endsWith(CLASS_SUFFIX)) {
        return null
      }
      return entry
        .replace(MULTI_RELEASE_PREFIX, "")
        .removeSuffix(CLASS_SUFFIX)
        .replace('/', '.')
        .replace('$', '.')
    }

    private fun writeAtomically(
      path: Path,
      lines: List<String>,
    ) {
      val temp = path.resolveSibling(path.fileName.toString() + ".tmp")
      Files.write(temp, lines, StandardCharsets.UTF_8)
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun deleteRecursively(path: Path) {
      if (Files.exists(path)) {
        path.toFile().deleteRecursively()
      }
    }

    private fun sha256(bytes: ByteArray): String =
      MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
  }
}
