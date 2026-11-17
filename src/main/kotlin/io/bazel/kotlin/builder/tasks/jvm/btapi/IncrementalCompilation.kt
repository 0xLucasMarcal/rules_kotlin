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
package io.bazel.kotlin.builder.tasks.jvm.btapi

import io.bazel.kotlin.builder.tasks.BtapiRuntime
import io.bazel.kotlin.builder.tasks.jvm.SOURCE_JARS_DIR
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.BACKUP
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.COMMIT
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.COMPILE
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.OUTPUTS
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.SNAPSHOTS
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Companion.STAGING
import io.bazel.kotlin.builder.tasks.jvm.btapi.CompilationSummary.Mode
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.fingerprintOf
import io.bazel.kotlin.builder.utils.jars.SourceDependencies
import io.bazel.kotlin.builder.utils.jars.SourceDependenciesFile
import io.bazel.kotlin.compiler.CompilerPluginSpec
import io.bazel.kotlin.compiler.IncrementalCompilationSpec
import io.bazel.kotlin.compiler.KotlinBtapiCompiler
import io.bazel.kotlin.model.JvmCompilationTask
import java.io.IOException
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import java.util.stream.Collectors
import kotlin.concurrent.withLock

/** Bumped whenever the layout or the meaning of the persisted state changes. */
private const val STATE_FORMAT = "4"

/** The exit code of a compilation that failed on errors in the sources. */
private const val COMPILATION_ERROR = 1

/** How long a classpath snapshot that no compilation used is kept. */
private val SNAPSHOT_RETENTION: Duration = Duration.ofDays(3)

/** How long a worker trusts that a shared snapshot it saw still exists. */
private val SNAPSHOT_RECHECK: Duration = Duration.ofHours(1)

/** How often the usage report is refreshed. */
private val USAGE_REPORT_INTERVAL: Duration = Duration.ofDays(1)

/** The size the compilation log is rotated at. */
private const val LOG_ROTATION_BYTES = 64L * 1024 * 1024

/**
 * The jdeps plugin options that list the classpath. The state survives their changes: the
 * compiler detects classpath changes from the snapshots, and the builder writes the jdeps from the
 * dependencies of each source.
 */
private val JDEPS_CLASSPATH_OPTIONS = setOf("full_classpath")

/** An output path of the exec configuration, e.g. `bazel-out/k8-opt-exec-ST-1234/bin/...`. */
private val EXEC_CONFIGURATION_OUTPUT = Regex("""(^|/)bazel-out/([^/]*-exec[-/]|host/)""")

/**
 * Snapshot-based incremental compilation for the Build Tools API path.
 *
 * Bazel runs every compile action against empty output directories, while the Kotlin incremental
 * compiler compiles against the outputs and caches of the previous compilation. The worker keeps
 * that state per target under [root], outside of the action's directories, compiles into it, and
 * creates the action's output jars from it. The state of a target is only reused while the
 * compilation's configuration (compiler flags and plugins, the friend paths, the requested
 * outputs) is unchanged; any such change starts over with a full compilation.
 *
 * A compilation that does not complete leaves the state as it was: before the compiler changes
 * the state, the worker backs it up and marks the compilation in progress; a compilation error,
 * a cancelled request, or a killed worker restore the backup, on failure or at the next
 * compilation of the target.
 *
 * With [IncrementalCompilationOptions.lazyState], the first compilation of a target compiles
 * without state and only records a marker: the state is created by the target's next compilation,
 * a sign that someone iterates on it.
 *
 * File contents are identified by the digests Bazel sends with each work request, computed with
 * the build's `--digest_function`, so incremental compilation needs the persistent worker
 * protocol. Source changes are computed from those digests against the sources of the last
 * successful compilation; classpath changes, including added and removed entries, are detected by
 * the compiler from per-entry snapshots, which are shared across targets and keyed by the entry's
 * digest.
 *
 * @param checkpoints observes the points a compilation could be interrupted at, for tests.
 */
class IncrementalCompilation
  constructor(
    private val root: Path,
    val options: IncrementalCompilationOptions = IncrementalCompilationOptions(),
    private val checkpoints: CheckpointListener = CheckpointListener { _, _ -> },
  ) {
    private val targetLocks = ConcurrentHashMap<Path, TargetLock>()
    private val maintenanceStarted = AtomicBoolean(false)

    /** Shared snapshot names known to exist, with when they were last seen. */
    private val knownSnapshots = ConcurrentHashMap<String, Instant>()

    /** Shared snapshots being computed, so that concurrent compilations compute each once. */
    private val pendingSnapshots = ConcurrentHashMap<String, CompletableFuture<Void>>()

    /** Marks [root] as used by this worker; its disappearance means the root does not persist. */
    private val instanceMarker: Path =
      root.resolve(INSTANCES_DIR).resolve("${ProcessHandle.current().pid()}-${UUID.randomUUID()}")
    private var instanceMarked = false
    private var rootResets = false

    private val snapshotsDir: Path get() = root.resolve(SNAPSHOTS_DIR)
    private val trash: Path get() = root.resolve(TRASH_DIR)
    private val log: Path get() = root.resolve(LOG_FILE)

    /** Deletes discarded state and maintains the root, off the compilations' threads. */
    private val background: ExecutorService by lazy {
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kotlin-incremental-compilation").apply { isDaemon = true }
      }
    }

    /** Computes missing shared snapshots in parallel. */
    private val snapshotters: ExecutorService by lazy {
      Executors.newFixedThreadPool(
        maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
      ) { runnable ->
        Thread(runnable, "kotlin-classpath-snapshot").apply { isDaemon = true }
      }
    }

    /** Whether hard links work under [root]; copies replace them otherwise. */
    @Volatile
    private var linksSupported = true

    /** The points of a compilation an interruption would leave the state at. */
    enum class Checkpoint {
      /** The state is backed up and the compilation is in progress. */
      BACKED_UP,

      /** The compiler wrote the outputs and caches. */
      COMPILED,

      /** The record of the compilation is written, which is not complete yet. */
      RECORDED,
    }

    fun interface CheckpointListener {
      fun reached(
        checkpoint: Checkpoint,
        stateDirectory: Path,
      )
    }

    /** A target's in-process lock, removed once no compilation holds or awaits it. */
    private class TargetLock {
      val lock = ReentrantLock()

      /** The compilations holding or awaiting [lock]; changed only within [targetLocks]' compute. */
      var users = 0
    }

    /** Where the compiler plugins of a compilation write to, see [compile]. */
    class PluginOutputs(
      /** The directory the ABI plugin writes the ABI classes to. */
      val abiClasses: String,
    )

    /** What [compile] hands to the compiler invocation. */
    class Invocation(
      val sources: List<String>,
      val destination: String,
      val plugins: List<CompilerPluginSpec>,
      val incrementalCompilation: IncrementalCompilationSpec,
    )

    /** The result of [compile]. */
    class Result(
      /** The compiler's output. */
      val output: List<String>,
      /** Whether [compile] created the output jars; otherwise the caller creates them. */
      val packaged: Boolean,
    )

    private class Spec(
      override val workingDirectory: String,
      override val sourceChangesKnown: Boolean,
      override val modifiedSources: List<String>,
      override val removedSources: List<String>,
      override val classpathSnapshots: List<String>,
      override val classpathUnchanged: Boolean,
      override val additionalOutputDirectories: List<String>,
      private val compiled: MutableSet<String>,
    ) : IncrementalCompilationSpec {
      override fun compiledSources(sources: List<String>) {
        compiled.addAll(sources)
      }
    }

    /** A compilation's record: of the last successful compilation, or the lazy-state marker. */
    private class Record(
      val fingerprint: String,
      /** stable source path -> content digest */
      val sources: Map<String, String>,
      /** The digests of the classpath entries in order, or null if an entry had none. */
      val classpath: List<String>? = null,
      /** The digests of the source jars in order, or null if a jar had none. */
      val sourceJars: List<String>? = null,
    )

    /** How a part of the state is backed up while a compilation is in progress. */
    private enum class Backup {
      /** A file written by replacing it: a link to it keeps the earlier content. */
      LINKED_FILE,

      /** Class files, which the compiler replaces rather than rewrites, see [begin]. */
      LINKED_TREE,

      /** Files updated in place. */
      COPIED_TREE,
    }

    /** The parts of the state a compilation changes, which [rollBack] restores together. */
    private enum class Component(
      val fileName: String,
      val backup: Backup,
    ) {
      RECORD("state", Backup.LINKED_FILE),
      MARKER("marker", Backup.LINKED_FILE),
      SOURCE_DEPENDENCIES("source_deps", Backup.LINKED_FILE),
      CLASSES("classes", Backup.LINKED_TREE),
      ABI_CLASSES("abi_classes", Backup.LINKED_TREE),
      CACHES("caches", Backup.COPIED_TREE),
    }

    /** The state directory of one target. */
    private class TargetState(
      val dir: Path,
    ) {
      val lockFile: Path = dir.resolve(LOCK_FILE)
      val record: Path = dir.resolve(Component.RECORD.fileName)

      /** The lazy-state marker: the target compiled once without state. */
      val marker: Path = dir.resolve(Component.MARKER.fileName)
      val inProgress: Path = dir.resolve("in-progress")
      val backup: Path = dir.resolve("backup")
      val caches: Path = dir.resolve(Component.CACHES.fileName)
      val classes: Path = dir.resolve(Component.CLASSES.fileName)
      val abiClasses: Path = dir.resolve(Component.ABI_CLASSES.fileName)
      val abiRound: Path = dir.resolve("abi_round")

      /** The dependencies of each source of the last successful compilation. */
      val sourceDependencies: Path = dir.resolve(Component.SOURCE_DEPENDENCIES.fileName)
      val stagedSources: Path = dir.resolve("srcs")

      /** Snapshots of the classpath entries the request has no digest for, recomputed each time. */
      val classpathSnapshots: Path = dir.resolve("classpath-snapshots")

      companion object {
        const val LOCK_FILE = ".lock"
      }
    }

    /**
     * Compiles the task, incrementally when it can.
     *
     * @param classpath the compile classpath.
     * @param sourceDependencies the file the jdeps plugin appends the dependencies of the sources
     *   it analyzes to. Once the compilation completes, it holds the dependencies of every source.
     * @param blocker why the caller cannot compile the task incrementally, or null if it can.
     * @param plugins the compiler plugins, given where they write to.
     * @param invoke runs the compiler incrementally and returns its output lines; throws
     *   [CompilationStatusException] when the compilation fails.
     * @param plain compiles the task without state into the action's directories.
     * @param packageOutputs creates the output jars from the directories of the classes and of the
     *   ABI classes, while the target's state is locked.
     */
    fun compile(
      context: CompilationTaskContext,
      task: JvmCompilationTask,
      runtime: BtapiRuntime,
      compiler: KotlinBtapiCompiler,
      classpath: List<String>,
      sourceDependencies: Path,
      blocker: String?,
      plugins: (PluginOutputs) -> List<CompilerPluginSpec>,
      invoke: (Invocation) -> List<String>,
      plain: () -> List<String>,
      packageOutputs: (classes: Path, abiClasses: Path) -> Unit,
    ): Result {
      val summary = CompilationSummary(task.info.label)
      summary.sources = task.inputs.kotlinSourcesCount + task.inputs.javaSourcesCount
      try {
        (blocker ?: taskBlocker(task))?.let { reason ->
          context.whenTracing { printLines("incremental compilation skipped", listOf(reason)) }
          summary.mode = Mode.SKIPPED
          summary.reason = reason
          return Result(summary.time(COMPILE, plain), packaged = false)
        }
        val state =
          try {
            unavailability(context)?.let { reason ->
              context.whenTracing {
                printLines("incremental compilation unavailable", listOf(reason))
              }
              summary.mode = Mode.UNAVAILABLE
              summary.reason = reason
              return Result(summary.time(COMPILE, plain), packaged = false)
            }
            startMaintenance()
            TargetState(Files.createDirectories(root.resolve(targetKey(task))))
          } catch (e: IOException) {
            context.whenTracing {
              printLines("incremental compilation unavailable", listOf(e.toString()))
            }
            summary.mode = Mode.UNAVAILABLE
            summary.reason = e.toString()
            return Result(summary.time(COMPILE, plain), packaged = false)
          }
        return withTargetLock(state) {
          compileWithState(
            context,
            task,
            runtime,
            compiler,
            classpath,
            sourceDependencies,
            plugins,
            invoke,
            plain,
            packageOutputs,
            state,
            summary,
          )
        }
      } catch (e: Throwable) {
        summary.failure =
          if (e is CompilationStatusException) "exit code ${e.status}" else e.toString()
        throw e
      } finally {
        report(context, summary)
      }
    }

    /** Why the task does not compile incrementally on any worker, or null if it does. */
    private fun taskBlocker(task: JvmCompilationTask): String? {
      val generated =
        listOf(
          Paths.get(task.directories.temp).resolve(SOURCE_JARS_DIR),
          Paths.get(task.directories.generatedSources),
          Paths.get(task.directories.generatedJavaSources),
        ).map { it.toAbsolutePath().normalize() }
      val handwritten =
        (task.inputs.kotlinSourcesList + task.inputs.javaSourcesList).count { source ->
          val path = Paths.get(source).toAbsolutePath().normalize()
          generated.none(path::startsWith)
        }
      return when {
        task.info.incrementalCompilationOptOut -> {
          "the target opts out with the kt_no_incremental_compilation tag"
        }

        !options.execConfiguration &&
          EXEC_CONFIGURATION_OUTPUT.containsMatchIn(
            task.outputs.jar.ifEmpty { task.outputs.abijar },
          ) -> {
          "the target is built for the exec configuration"
        }

        handwritten == 0 -> {
          "the target has no sources outside source jars and generated directories"
        }

        handwritten < options.minSources -> {
          "the target has $handwritten sources outside source jars and generated directories, " +
            "fewer than ${options.minSources}"
        }

        else -> {
          null
        }
      }
    }

    /** Why the worker cannot compile incrementally, or null if it can. */
    private fun unavailability(context: CompilationTaskContext): String? =
      when {
        context.inputDigests.isEmpty() -> {
          "the request has no input digests: the action does not run in a persistent worker"
        }

        !rootPersists(context) -> {
          "$root is reset between requests, e.g. by a sandboxed worker"
        }

        else -> {
          null
        }
      }

    /**
     * Whether [root] keeps its contents between requests. Bazel resets the directory a sandboxed
     * worker runs in before each request, which discards the state: the first compilation marks the
     * root for this worker, and once the marker disappears, the worker compiles non-incrementally.
     */
    @Synchronized
    private fun rootPersists(context: CompilationTaskContext): Boolean {
      if (rootResets) {
        return false
      }
      if (!instanceMarked) {
        Files.createDirectories(instanceMarker.parent)
        Files.write(instanceMarker, ByteArray(0))
        instanceMarker.toFile().deleteOnExit()
        instanceMarked = true
      } else if (!Files.exists(instanceMarker)) {
        rootResets = true
        context.printLines(
          "warning: incremental compilation disabled",
          listOf("$root is reset between requests, e.g. by a sandboxed worker"),
        )
      }
      return !rootResets
    }

    private fun compileWithState(
      context: CompilationTaskContext,
      task: JvmCompilationTask,
      runtime: BtapiRuntime,
      compiler: KotlinBtapiCompiler,
      classpath: List<String>,
      sourceDependencies: Path,
      plugins: (PluginOutputs) -> List<CompilerPluginSpec>,
      invoke: (Invocation) -> List<String>,
      plain: () -> List<String>,
      packageOutputs: (classes: Path, abiClasses: Path) -> Unit,
      state: TargetState,
      summary: CompilationSummary,
    ): Result {
      val digests = context.inputDigests
      val requestRoot = requestRoot(task)
      val runtimeKey =
        runtime.classpathFingerprint.ifEmpty { fingerprintOf(runtime.apiImplClasspath, digests) }
      val pluginSpecs = plugins(PluginOutputs(state.abiRound.toString()))
      val fingerprint = fingerprint(task, runtimeKey, pluginSpecs, requestRoot, digests)
      // The last use of the state, see [maintain].
      Files.setLastModifiedTime(state.lockFile, FileTime.from(Instant.now()))

      var interrupted = false
      var restored = false
      if (Files.exists(state.inProgress)) {
        interrupted = true
        restored = rollBack(state)
        if (!restored) {
          reset(state)
        }
      }
      var previous = readPreviousCompilation(state, task)
      if (previous?.fingerprint != fingerprint) {
        val marker = readRecord(state.marker)
        val why =
          when {
            interrupted && !restored -> "the previous compilation did not complete"
            previous == null -> "no previous compilation state"
            else -> "the compilation configuration changed"
          }
        reset(state)
        previous = null
        if (options.lazyState && marker?.fingerprint != fingerprint) {
          // Only a target that compiles again gets a state.
          context.whenTracing {
            printLines(
              "incremental compilation",
              listOf("$why, compiling without state: the next compilation creates it"),
            )
          }
          summary.mode = Mode.PLAIN
          summary.reason = if (marker == null) why else "the compilation configuration changed"
          // Before compiling: a compilation that fails, e.g. on errors someone is fixing, counts.
          writeRecord(
            state.marker,
            Record(
              fingerprint,
              (task.inputs.javaSourcesList + task.inputs.kotlinSourcesList).associateWith {
                digests.digestOf(it) ?: "-"
              },
            ),
          )
          return Result(summary.time(COMPILE, plain), packaged = false)
        }
        context.whenTracing {
          printLines("incremental compilation", listOf("$why, compiling from scratch"))
        }
        summary.reason =
          if (marker?.fingerprint == fingerprint) "the target compiles again" else why
      } else if (restored) {
        val why = "the previous compilation did not complete, restored the state before it"
        context.whenTracing { printLines("incremental compilation", listOf(why)) }
        summary.reason = why
      }
      summary.mode = if (previous == null) Mode.FULL else Mode.INCREMENTAL

      Files.createDirectories(state.caches)
      Files.createDirectories(state.classes)
      Files.createDirectories(state.abiClasses)
      val classesBefore = summary.time(BACKUP) { begin(state) }
      checkpoints.reached(Checkpoint.BACKED_UP, state.dir)
      val output =
        try {
          compileInProgress(
            context,
            task,
            compiler,
            classpath,
            sourceDependencies,
            invoke,
            state,
            summary,
            previous,
            fingerprint,
            runtimeKey,
            pluginSpecs,
            classesBefore,
          )
        } catch (e: Throwable) {
          abandon(context, state, e)
          throw e
        }
      summary.time(OUTPUTS) { packageOutputs(state.classes, state.abiClasses) }
      return Result(output, packaged = true)
    }

    /**
     * The part of a compilation between [begin] and [commit]: everything it changes in the state is
     * rolled back if it does not complete.
     */
    private fun compileInProgress(
      context: CompilationTaskContext,
      task: JvmCompilationTask,
      compiler: KotlinBtapiCompiler,
      classpath: List<String>,
      sourceDependencies: Path,
      invoke: (Invocation) -> List<String>,
      state: TargetState,
      summary: CompilationSummary,
      previous: Record?,
      fingerprint: String,
      runtimeKey: String,
      pluginSpecs: List<CompilerPluginSpec>,
      classesBefore: Map<String, FileStamp>,
    ): List<String> {
      val digests = context.inputDigests
      val sourceJarDigests = task.inputs.sourceJarsList.map(digests::digestOf)
      val sources =
        summary.time(STAGING) {
          stageSources(task, requestRoot(task), state, digests, previous, sourceJarDigests)
        }
      val changed = sources.filter { (path, digest) -> previous?.sources?.get(path) != digest }.keys
      val kept = keptSourceDependencies(task, state, sources.keys, previous != null)
      val stale = staleSourceDependencies(task, kept)
      // A stale record of no source in particular is recorded again by recompiling every source.
      val sourceByPath = sources.keys.associateBy(::canonicalPath)
      val recordAgain = stale.map { if (it.isEmpty()) null else sourceByPath[canonicalPath(it)] }
      val modified =
        if (null in recordAgain) sources.keys else changed + recordAgain.filterNotNull()
      val removed = previous?.sources?.keys?.filterNot(sources::containsKey) ?: emptyList()
      val classpathDigests = classpath.map(digests::digestOf)
      // Assured of no classpath changes, the compiler does not compare the classpath snapshots.
      val classpathUnchanged =
        previous?.classpath != null &&
          null !in classpathDigests &&
          previous.classpath == classpathDigests
      if (previous != null) {
        summary.modified = modified.size
        summary.removed = removed.size
        summary.classpathUnchanged = classpathUnchanged
      }
      val snapshots =
        summary.time(SNAPSHOTS) {
          deleteRecursively(state.classpathSnapshots)
          snapshotsOf(classpath, state, runtimeKey, compiler, digests)
        }

      context.whenTracing {
        printLines(
          "incremental compilation",
          listOf("state: ${state.dir}") +
            if (previous == null) {
              listOf("source changes unknown")
            } else {
              changed.map { "modified: $it" } +
                stale.map { "uses a jar no longer on the classpath: $it" } +
                removed.map { "removed: $it" } +
                listOfNotNull("classpath unchanged".takeIf { classpathUnchanged })
            },
        )
      }

      discard(state.abiRound)
      Files.createDirectories(state.abiRound)
      if (task.outputs.jdeps.isNotEmpty()) {
        // The jdeps plugin appends the dependencies of the sources it analyzes, which replace their
        // earlier ones: it does not run when the compiler recompiles nothing, and analyzes only the
        // recompiled sources when it does. The state's copy only changes once the compilation
        // succeeds.
        SourceDependenciesFile.write(sourceDependencies, kept - stale)
      }

      val compiled = linkedSetOf<String>()
      val output =
        summary.time(COMPILE) {
          invoke(
            Invocation(
              sources = sources.keys.toList(),
              destination = state.classes.toString(),
              plugins = pluginSpecs,
              incrementalCompilation =
                Spec(
                  workingDirectory = state.caches.toString(),
                  sourceChangesKnown = previous != null,
                  modifiedSources = modified.toList(),
                  removedSources = removed,
                  classpathSnapshots = snapshots,
                  classpathUnchanged = classpathUnchanged,
                  additionalOutputDirectories =
                    listOf(state.abiClasses.toString(), state.abiRound.toString()),
                  compiled = compiled,
                ),
            ),
          )
        }
      summary.recompiled = compiled.size
      checkpoints.reached(Checkpoint.COMPILED, state.dir)

      summary.time(COMMIT) {
        val classesAfter = stamps(state.classes)
        if (task.outputs.abijar.isNotEmpty()) {
          mergeAbiClasses(state, classesBefore, classesAfter)
        }
        if (!classesAfter.keys.containsAll(classesBefore.keys)) {
          // A full compilation has no directory of a package without classes, nor have the jars.
          removeEmptyDirectories(state.classes)
          removeEmptyDirectories(state.abiClasses)
        }
        if (task.outputs.jdeps.isNotEmpty()) {
          // The plugin appended a record for each round: keep one per source.
          writeAtomically(state.sourceDependencies) {
            SourceDependenciesFile.write(it, SourceDependenciesFile.read(sourceDependencies))
          }
        }
        writeRecord(
          state.record,
          Record(
            fingerprint,
            sources,
            classpathDigests.takeIf { null !in it }?.filterNotNull(),
            sourceJarDigests.takeIf { null !in it }?.filterNotNull(),
          ),
        )
        checkpoints.reached(Checkpoint.RECORDED, state.dir)
        Files.deleteIfExists(state.marker)
        commit(state)
      }
      return output
    }

    /**
     * Backs up the state before a compilation changes it, then marks the compilation in progress:
     * until [commit], a compilation that does not complete is rolled back ([rollBack]). Returns the
     * stamps of the classes before the compilation.
     *
     * The backup links the class files rather than copying them. The compiler, which backs up its
     * outputs as well (BACKUP_CLASSES), moves an existing output away before writing it again, so a
     * link keeps the content of the file it was made from. The caches are updated in place, and
     * copied.
     */
    private fun begin(state: TargetState): Map<String, FileStamp> {
      discard(state.backup)
      Files.createDirectories(state.backup)
      var classes = emptyMap<String, FileStamp>()
      val saved = mutableListOf<String>()
      for (component in Component.values()) {
        val path = state.dir.resolve(component.fileName)
        if (!Files.exists(path)) {
          continue
        }
        val copy = state.backup.resolve(component.fileName)
        when (component.backup) {
          Backup.LINKED_FILE -> {
            linkOrCopy(path, copy)
          }

          Backup.LINKED_TREE -> {
            linkTree(path, copy).also { if (component == Component.CLASSES) classes = it }
          }

          Backup.COPIED_TREE -> {
            copyTree(path, copy)
          }
        }
        saved.add(component.fileName)
      }
      writeLines(state.inProgress, listOf("format $STATE_FORMAT") + saved.map { "backup $it" })
      return classes
    }

    /** Completes a compilation begun with [begin], once its record is written. */
    private fun commit(state: TargetState) {
      Files.delete(state.inProgress)
      runCatching { discard(state.backup) }
    }

    /**
     * Restores the state backed up by [begin] for a compilation that did not complete. Returns false
     * when there is no complete backup, and the state has to start over. An interrupted rollback is
     * completed by the next one.
     */
    private fun rollBack(state: TargetState): Boolean {
      val lines =
        try {
          Files.readAllLines(state.inProgress, StandardCharsets.UTF_8)
        } catch (_: IOException) {
          return false
        }
      if (lines.firstOrNull() != "format $STATE_FORMAT" ||
        lines.drop(1).any { !it.startsWith("backup ") }
      ) {
        return false
      }
      val saved = lines.drop(1).map { it.removePrefix("backup ") }.toSet()
      for (component in Component.values()) {
        val path = state.dir.resolve(component.fileName)
        val copy = state.backup.resolve(component.fileName)
        if (component.fileName !in saved) {
          discard(path)
        } else if (Files.exists(copy)) {
          discard(path)
          Files.move(copy, path, StandardCopyOption.ATOMIC_MOVE)
        }
        // Otherwise an interrupted rollback restored the component already.
      }
      discard(state.abiRound)
      // The sources staged for the compilation no longer match the record, see [stageSources].
      discard(state.stagedSources)
      Files.delete(state.inProgress)
      discard(state.backup)
      return true
    }

    /**
     * Undoes a compilation that failed. Errors in the sources and interruptions restore the state
     * before the compilation; anything else, e.g. an internal compiler error, could recur
     * incrementally, and the next compilation starts over.
     */
    private fun abandon(
      context: CompilationTaskContext,
      state: TargetState,
      failure: Throwable,
    ) {
      val restorable =
        (failure is CompilationStatusException && failure.status == COMPILATION_ERROR) ||
          failure is InterruptedException ||
          failure is ClosedByInterruptException
      try {
        if (restorable && rollBack(state)) {
          return
        }
        reset(state)
      } catch (e: IOException) {
        failure.addSuppressed(e)
      }
      context.whenTracing {
        printLines("incremental compilation", listOf("the compilation failed, discarded the state"))
      }
    }

    /** Discards the state, apart from the lock and the lazy-state marker. */
    private fun reset(state: TargetState) {
      // Without its record, a partially discarded state is incomplete.
      Files.deleteIfExists(state.record)
      Files
        .list(state.dir)
        .use { entries ->
          entries
            .filter {
              it.fileName.toString() !in
                setOf(TargetState.LOCK_FILE, Component.MARKER.fileName)
            }.collect(Collectors.toList())
        }.forEach(::discard)
    }

    /**
     * Removes [path]. A directory is moved to the root's trash and deleted in the background, off
     * the compilation's critical path.
     */
    private fun discard(path: Path) {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.deleteIfExists(path)
        return
      }
      val trashed =
        try {
          Files.createDirectories(trash)
          trash.resolve(UUID.randomUUID().toString()).also {
            Files.move(path, it, StandardCopyOption.ATOMIC_MOVE)
          }
        } catch (_: IOException) {
          deleteRecursively(path)
          return
        }
      background.execute { runCatching { deleteRecursively(trashed) } }
    }

    private fun <T> withTargetLock(
      state: TargetState,
      block: () -> T,
    ): T {
      val targetLock =
        targetLocks.compute(state.dir) { _, existing ->
          (existing ?: TargetLock()).apply { users++ }
        }!!
      try {
        return targetLock.lock.withLock { withFileLock(state, block) }
      } finally {
        targetLocks.compute(state.dir) { _, existing ->
          existing?.takeIf { --it.users > 0 }
        }
      }
    }

    /**
     * Runs [block] holding the file lock of the target, which serializes worker processes sharing
     * the state root, e.g. the singleplex worker instances of one Bazel server.
     */
    private fun <T> withFileLock(
      state: TargetState,
      block: () -> T,
    ): T {
      while (true) {
        Files.createDirectories(state.dir)
        val channel =
          FileChannel.open(state.lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        channel.use {
          channel.lock().use {
            // Unless the state was pruned while this compilation waited for the lock, see [maintain].
            if (Files.exists(state.lockFile)) {
              return block()
            }
          }
        }
      }
    }

    /**
     * The state directory name of the task's target. The label alone is ambiguous: a target can be
     * built in several configurations, which differ in their output paths.
     */
    private fun targetKey(task: JvmCompilationTask): String {
      val readable =
        task.info.label
          .replace(Regex("[^A-Za-z0-9._-]+"), "_")
          .trim('_')
          .takeLast(80)
      return "$readable-" + sha256("${task.info.label}\n${task.outputs.jar}").take(16)
    }

    /** The directory holding the action's own directories. */
    private fun requestRoot(task: JvmCompilationTask): Path =
      Paths
        .get(task.directories.temp)
        .toAbsolutePath()
        .normalize()
        .parent

    /**
     * Maps the sources to the paths the compiler reads them from. Sources extracted or generated
     * into the action's own directories (source jar contents, generated sources) live under a
     * different directory in every action, so they are staged under the target's state directory:
     * the incremental compiler tracks sources by path. Returns the content digest of each source by
     * its compiled path, in compilation order: Bazel's digest of an input, or, for the files Bazel
     * never saw, a SHA-256 of the content.
     *
     * A staged source is only read and hashed when it may have changed: the staged sources match
     * the record of the last successful compilation, as [rollBack] discards them otherwise, so
     * source jars with the digests of that compilation extract the staged files, and a staged file
     * with the content of the new one keeps its recorded digest.
     */
    private fun stageSources(
      task: JvmCompilationTask,
      requestRoot: Path,
      state: TargetState,
      inputDigests: Map<String, String>,
      previous: Record?,
      sourceJarDigests: List<String?>,
    ): Map<String, String> {
      val sourceJarsUnchanged =
        previous?.sourceJars != null && null !in sourceJarDigests &&
          previous.sourceJars == sourceJarDigests
      val sourceJarContents =
        Paths
          .get(task.directories.temp)
          .resolve(SOURCE_JARS_DIR)
          .toAbsolutePath()
          .normalize()
      val sources = LinkedHashMap<String, String>()
      val staged = mutableSetOf<Path>()
      for (source in task.inputs.javaSourcesList + task.inputs.kotlinSourcesList) {
        val path = Paths.get(source).toAbsolutePath().normalize()
        if (!path.startsWith(requestRoot)) {
          sources[path.toString()] =
            inputDigests.digestOf(source) ?: sha256(Files.readAllBytes(path))
          continue
        }
        val target = state.stagedSources.resolve(requestRoot.relativize(path).toString())
        staged.add(target)
        val recorded = previous?.sources?.get(target.toString())
        if (recorded != null &&
          sourceJarsUnchanged &&
          path.startsWith(sourceJarContents) &&
          Files.isRegularFile(target)
        ) {
          sources[target.toString()] = recorded
          continue
        }
        val content = Files.readAllBytes(path)
        val unchanged =
          Files.isRegularFile(target) &&
            Files.size(target) == content.size.toLong() &&
            Files.readAllBytes(target).contentEquals(content)
        if (!unchanged) {
          Files.createDirectories(target.parent)
          Files.write(target, content)
        }
        sources[target.toString()] = recorded?.takeIf { unchanged } ?: sha256(content)
      }
      val previouslyStaged =
        previous
          ?.sources
          ?.keys
          ?.filter { it.startsWith(state.stagedSources.toString()) }
          ?.map { Paths.get(it) }
          ?.toSet()
      if (previouslyStaged != staged && Files.isDirectory(state.stagedSources)) {
        Files.walk(state.stagedSources).use { paths ->
          paths
            .filter { Files.isRegularFile(it) && it !in staged }
            .collect(Collectors.toList())
            .forEach(Files::delete)
        }
      }
      return sources
    }

    /**
     * The dependencies of the sources of the last successful compilation the target still has: none
     * without a previous compilation.
     */
    private fun keptSourceDependencies(
      task: JvmCompilationTask,
      state: TargetState,
      sources: Set<String>,
      hasPrevious: Boolean,
    ): Map<String, SourceDependencies> {
      if (task.outputs.jdeps.isEmpty() || !hasPrevious) {
        return emptyMap()
      }
      // The compiler reports a source by the path it was given, which may be a link.
      val current = sources.map(::canonicalPath).toSet()
      return SourceDependenciesFile.read(state.sourceDependencies).filterKeys {
        it.isEmpty() || canonicalPath(it) in current
      }
    }

    /**
     * The sources whose dependencies list a jar that is no longer on the classpath. The compiler
     * sees no change in a jar that moved with the same contents, e.g. a dependency that moved to
     * another package, so the sources using it are not recompiled and would keep the jar's old path.
     */
    private fun staleSourceDependencies(
      task: JvmCompilationTask,
      dependencies: Map<String, SourceDependencies>,
    ): Set<String> {
      val classpath = task.inputs.classpathList.toSet()
      return dependencies
        .filterValues { used ->
          used.explicitJars.any { it !in classpath } || used.implicitJars.any { it !in classpath }
        }.keys
    }

    /**
     * The ABI plugin writes the ABI of the classes compiled in this round only. Replaces the ABI of
     * every class the compilation wrote or deleted with this round's output: a class the plugin did
     * not emit again was removed from the ABI (or from the sources). A replaced file is deleted
     * first, which keeps its backup link intact, see [begin].
     */
    private fun mergeAbiClasses(
      state: TargetState,
      before: Map<String, FileStamp>,
      after: Map<String, FileStamp>,
    ) {
      (before.keys + after.keys)
        .filter { before[it] != after[it] }
        .forEach { Files.deleteIfExists(state.abiClasses.resolve(it)) }
      Files.walk(state.abiRound).use { paths ->
        paths.forEach { source ->
          val target = state.abiClasses.resolve(state.abiRound.relativize(source).toString())
          if (Files.isDirectory(source)) {
            Files.createDirectories(target)
          } else {
            Files.deleteIfExists(target)
            Files.copy(source, target)
          }
        }
      }
    }

    /**
     * The snapshots of the classpath entries, in classpath order. The snapshot of an entry with a
     * digest is computed once per digest and shared across targets; the missing ones are computed
     * in parallel. An entry the request has no digest for, e.g. a directory, is snapshotted for this
     * compilation only, and skipped if it does not exist, as the compiler skips it as well.
     */
    private fun snapshotsOf(
      classpath: List<String>,
      state: TargetState,
      runtimeKey: String,
      compiler: KotlinBtapiCompiler,
      inputDigests: Map<String, String>,
    ): List<String> {
      val snapshots = arrayOfNulls<String>(classpath.size)
      val pending = mutableListOf<CompletableFuture<Void>>()
      val now = Instant.now()
      classpath.forEachIndexed { index, entry ->
        // External repositories rarely change; class-level granularity keeps their snapshots small.
        val classLevel = entry.startsWith("external/") || "/external/" in entry
        val digest = inputDigests.digestOf(entry)
        if (digest == null) {
          val path = Paths.get(entry)
          if (Files.exists(path)) {
            Files.createDirectories(state.classpathSnapshots)
            val snapshot = state.classpathSnapshots.resolve("$index.bin")
            compiler.snapshotClasspathEntry(
              path.toAbsolutePath().toString(),
              snapshot.toString(),
              classLevel,
            )
            snapshots[index] = snapshot.toString()
          }
          return@forEachIndexed
        }
        val name = sha256("$STATE_FORMAT\n$runtimeKey\n$digest\n$classLevel")
        val snapshot = snapshotsDir.resolve("$name.bin")
        snapshots[index] = snapshot.toString()
        val seen = knownSnapshots[name]
        if (seen != null && seen.plus(SNAPSHOT_RECHECK).isAfter(now)) {
          return@forEachIndexed
        }
        if (Files.exists(snapshot)) {
          // Keeps it from being pruned, see [maintain].
          Files.setLastModifiedTime(snapshot, FileTime.from(now))
          knownSnapshots[name] = now
        } else {
          pending.add(
            sharedSnapshot(name, snapshot) {
              compiler.snapshotClasspathEntry(
                Paths.get(entry).toAbsolutePath().toString(),
                it,
                classLevel,
              )
            },
          )
        }
      }
      try {
        pending.forEach { it.join() }
      } catch (e: CompletionException) {
        throw e.cause ?: e
      }
      return snapshots.filterNotNull()
    }

    /** Computes a shared snapshot, once across the concurrent compilations of this worker. */
    private fun sharedSnapshot(
      name: String,
      snapshot: Path,
      compute: (String) -> Unit,
    ): CompletableFuture<Void> {
      val future =
        pendingSnapshots.computeIfAbsent(name) {
          CompletableFuture.runAsync({
            if (!Files.exists(snapshot)) {
              Files.createDirectories(snapshotsDir)
              val temporary = Files.createTempFile(snapshotsDir, name, ".tmp")
              try {
                compute(temporary.toString())
                Files.move(temporary, snapshot, StandardCopyOption.ATOMIC_MOVE)
              } catch (_: FileAlreadyExistsException) {
                // Computed concurrently by another worker.
              } finally {
                Files.deleteIfExists(temporary)
              }
            }
            knownSnapshots[name] = Instant.now()
          }, snapshotters)
        }
      future.whenComplete { _, _ -> pendingSnapshots.remove(name, future) }
      return future
    }

    /**
     * Everything a compilation's outputs depend on besides the sources and the classpath, with the
     * action-specific directory replaced by a placeholder.
     */
    private fun fingerprint(
      task: JvmCompilationTask,
      runtimeKey: String,
      plugins: List<CompilerPluginSpec>,
      requestRoot: Path,
      inputDigests: Map<String, String>,
    ): String {
      val lines = mutableListOf<String>()

      fun add(
        key: String,
        value: Any,
      ) {
        lines.add("$key=${value.toString().replace(requestRoot.toString(), "<action>")}")
      }
      add("format", STATE_FORMAT)
      add("runtime", runtimeKey)
      // The compiler resolves the JDK classes against the worker's JVM, which no snapshot covers.
      add("jdk", System.getProperty("java.home"))
      add("jdk_version", System.getProperty("java.runtime.version"))
      with(task.info) {
        add("module", moduleName)
        add("jvm_target", toolchainInfo.jvm.jvmTarget)
        add("api_version", toolchainInfo.common.apiVersion)
        add("language_version", toolchainInfo.common.languageVersion)
        passthroughFlagsList.forEach { add("flag", it) }
        friendPathsList.forEach { add("friend", it) }
        add("reduced_classpath", reducedClasspathMode)
      }
      plugins.forEach { plugin ->
        add("plugin", plugin.id)
        plugin.classpath.forEach {
          // A plugin jar the request has no digest for cannot be tracked: never reuse the state.
          add("plugin_classpath", "$it ${inputDigests.digestOf(it) ?: UUID.randomUUID()}")
        }
        plugin.options
          .filterNot {
            plugin.id == JDEPS_GEN_PLUGIN_ID && it.substringBefore('=') in JDEPS_CLASSPATH_OPTIONS
          }.forEach { add("plugin_option", it) }
      }
      add("jdeps", task.outputs.jdeps.isNotEmpty())
      add("abi", task.outputs.abijar.isNotEmpty())
      return sha256(lines.joinToString("\n"))
    }

    /** The record of the last successful compilation, if its state is complete. */
    private fun readPreviousCompilation(
      state: TargetState,
      task: JvmCompilationTask,
    ): Record? {
      val complete =
        Files.isDirectory(state.caches) &&
          Files.isDirectory(state.classes) &&
          (task.outputs.abijar.isEmpty() || Files.isDirectory(state.abiClasses)) &&
          (task.outputs.jdeps.isEmpty() || Files.isRegularFile(state.sourceDependencies))
      return if (complete) readRecord(state.record) else null
    }

    private fun readRecord(path: Path): Record? {
      val lines =
        try {
          Files.readAllLines(path, StandardCharsets.UTF_8)
        } catch (_: NoSuchFileException) {
          return null
        }
      if (lines.firstOrNull() != "format $STATE_FORMAT") {
        return null
      }
      var fingerprint: String? = null
      var classpath: List<String>? = null
      var sourceJars: List<String>? = null
      val sources = LinkedHashMap<String, String>()
      for (line in lines.drop(1)) {
        val fields = line.split(" ", limit = 3)
        when (fields[0]) {
          "fingerprint" -> fingerprint = fields[1]
          "source" -> sources[fields[2]] = fields[1]
          "classpath" -> classpath = digestList(fields.getOrElse(1) { "" })
          "source_jars" -> sourceJars = digestList(fields.getOrElse(1) { "" })
          else -> return null
        }
      }
      return fingerprint?.let { Record(it, sources, classpath, sourceJars) }
    }

    private fun digestList(text: String): List<String> =
      if (text.isEmpty()) emptyList() else text.split(',')

    private fun writeRecord(
      path: Path,
      record: Record,
    ) {
      writeLines(
        path,
        listOf("format $STATE_FORMAT", "fingerprint ${record.fingerprint}") +
          listOfNotNull(
            record.classpath?.let { "classpath ${it.joinToString(",")}" },
            record.sourceJars?.let { "source_jars ${it.joinToString(",")}" },
          ) +
          record.sources.map { (path, digest) -> "source $digest $path" },
      )
    }

    /** Replaces [path] with [lines] at once. */
    private fun writeLines(
      path: Path,
      lines: List<String>,
    ) = writeAtomically(path) { Files.write(it, lines, StandardCharsets.UTF_8) }

    /** Replaces [path] with the file [write] writes, at once: a link to the old file keeps it. */
    private fun writeAtomically(
      path: Path,
      write: (Path) -> Unit,
    ) {
      val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".tmp")
      try {
        write(temporary)
        Files.move(
          temporary,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } finally {
        Files.deleteIfExists(temporary)
      }
    }

    /** Links [target] to the file [source], or copies it where links are not supported. */
    private fun linkOrCopy(
      source: Path,
      target: Path,
    ) {
      if (linksSupported) {
        try {
          Files.createLink(target, source)
          return
        } catch (_: UnsupportedOperationException) {
          linksSupported = false
        } catch (e: IOException) {
          if (e is FileAlreadyExistsException || e is NoSuchFileException) {
            throw e
          }
          linksSupported = false
        }
      }
      Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES)
    }

    /** Recreates the tree [from] at [to] with links to its files; returns the files' stamps. */
    private fun linkTree(
      from: Path,
      to: Path,
    ): Map<String, FileStamp> {
      val stamps = HashMap<String, FileStamp>()
      Files.walk(from).use { paths ->
        paths.forEach { source ->
          val relative = from.relativize(source).toString()
          val target = to.resolve(relative)
          val attributes =
            Files.readAttributes(source, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
          if (attributes.isDirectory) {
            Files.createDirectories(target)
          } else {
            stamps[relative] = FileStamp.of(attributes)
            linkOrCopy(source, target)
          }
        }
      }
      return stamps
    }

    private fun report(
      context: CompilationTaskContext,
      summary: CompilationSummary,
    ) {
      if (options.summary) {
        context.printCompilerOutput(listOf(summary.line()))
      } else {
        context.whenTracing {
          printLines(
            "incremental compilation summary",
            listOf(summary.line()),
          )
        }
      }
      if (options.log) {
        appendLog(summary.json())
      }
    }

    @Synchronized
    private fun appendLog(line: String) {
      runCatching {
        Files.createDirectories(root)
        Files.write(
          log,
          (line + "\n").toByteArray(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
        )
      }
    }

    private fun startMaintenance() {
      if (maintenanceStarted.compareAndSet(false, true)) {
        background.execute { runCatching { maintain() } }
      }
    }

    /**
     * Prunes the root, once per worker: deletes the state of the targets and the shared snapshots
     * no compilation used for a while, the leftovers of discarded state and of exited workers, and
     * refreshes the usage report in `usage.json`.
     */
    private fun maintain() {
      val now = Instant.now()
      entries(trash).forEach { runCatching { deleteRecursively(it) } }
      entries(root.resolve(INSTANCES_DIR))
        .filter { it != instanceMarker && !workerAlive(it) }
        .forEach { runCatching { Files.deleteIfExists(it) } }
      entries(snapshotsDir)
        .filter { lastModified(it).isBefore(now.minus(SNAPSHOT_RETENTION)) }
        .forEach { runCatching { Files.deleteIfExists(it) } }

      var prunedTargets = 0
      var prunedBytes = 0L
      val retained = now.minus(options.retention)
      entries(root)
        .filter { Files.isDirectory(it) && it.fileName.toString() !in RESERVED_NAMES }
        .forEach { dir ->
          val lastUse =
            lastModified(dir.resolve(TargetState.LOCK_FILE).takeIf(Files::exists) ?: dir)
          if (lastUse.isBefore(retained)) {
            runCatching {
              val bytes = sizeOf(dir)
              if (pruneTarget(dir)) {
                prunedTargets++
                prunedBytes += bytes
              }
            }
          }
        }

      if (Files.exists(log) && Files.size(log) > LOG_ROTATION_BYTES) {
        Files.move(log, root.resolve("$LOG_FILE.1"), StandardCopyOption.REPLACE_EXISTING)
      }
      val usage = root.resolve(USAGE_FILE)
      if (prunedTargets > 0 ||
        !Files.exists(usage) ||
        lastModified(usage).isBefore(now.minus(USAGE_REPORT_INTERVAL))
      ) {
        writeUsageReport(usage, now, prunedTargets, prunedBytes)
      }
      if (options.log && (prunedTargets > 0)) {
        appendLog(
          "{\"time\":${CompilationSummary.quote(now.toString())},\"event\":\"prune\"," +
            "\"targets\":$prunedTargets,\"bytes\":$prunedBytes}",
        )
      }
    }

    /**
     * Discards a target's state unless a compilation holds or awaits it. Takes the target's locks
     * as a compilation does: the file lock of a worker process cannot be taken twice.
     */
    private fun pruneTarget(dir: Path): Boolean {
      val targetLock =
        targetLocks.compute(dir) { _, existing -> (existing ?: TargetLock()).apply { users++ } }!!
      try {
        if (!targetLock.lock.tryLock()) {
          return false
        }
        try {
          FileChannel
            .open(
              dir.resolve(TargetState.LOCK_FILE),
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
            ).use { channel ->
              val lock = channel.tryLock() ?: return false
              lock.use { discard(dir) }
            }
          return true
        } finally {
          targetLock.lock.unlock()
        }
      } finally {
        targetLocks.compute(dir) { _, existing -> existing?.takeIf { --it.users > 0 } }
      }
    }

    /** Writes the sizes of the root's parts, for `bazel-out/_kotlin_ic/usage.json`. */
    private fun writeUsageReport(
      usage: Path,
      now: Instant,
      prunedTargets: Int,
      prunedBytes: Long,
    ) {
      val targets =
        entries(root)
          .filter { Files.isDirectory(it) && it.fileName.toString() !in RESERVED_NAMES }
          .map { dir ->
            Triple(
              dir.fileName.toString(),
              sizeOf(dir),
              lastModified(dir.resolve(TargetState.LOCK_FILE).takeIf(Files::exists) ?: dir),
            )
          }.sortedByDescending { it.second }
      val snapshots = entries(snapshotsDir)
      val json =
        buildString {
          append("{\n")
          append("  \"time\": ").append(CompilationSummary.quote(now.toString())).append(",\n")
          append("  \"retention_days\": ").append(options.retention.toDays()).append(",\n")
          append("  \"total_bytes\": ").append(sizeOf(root)).append(",\n")
          append("  \"targets\": ").append(targets.size).append(",\n")
          append("  \"target_bytes\": ").append(targets.sumOf { it.second }).append(",\n")
          append("  \"snapshots\": ").append(snapshots.size).append(",\n")
          append("  \"snapshot_bytes\": ").append(snapshots.sumOf { sizeOf(it) }).append(",\n")
          append("  \"pruned_targets\": ").append(prunedTargets).append(",\n")
          append("  \"pruned_bytes\": ").append(prunedBytes).append(",\n")
          append("  \"largest_targets\": [")
          targets.take(20).forEachIndexed { index, (name, bytes, lastUse) ->
            append(if (index == 0) "\n" else ",\n")
            append("    {\"target\": ").append(CompilationSummary.quote(name))
            append(", \"bytes\": ").append(bytes)
            append(
              ", \"last_use\": ",
            ).append(CompilationSummary.quote(lastUse.toString())).append('}')
          }
          append("\n  ]\n}\n")
        }
      writeAtomically(usage) { Files.write(it, json.toByteArray(StandardCharsets.UTF_8)) }
    }

    /** Whether the worker that created the instance marker still runs. */
    private fun workerAlive(marker: Path): Boolean {
      val pid =
        marker.fileName
          .toString()
          .substringBefore('-')
          .toLongOrNull() ?: return false
      return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private companion object {
      /** Holds the markers of the workers using the root, see [rootPersists]. */
      const val INSTANCES_DIR = "workers"
      const val SNAPSHOTS_DIR = "classpath-snapshots"
      const val TRASH_DIR = "trash"
      const val LOG_FILE = "log.jsonl"
      const val USAGE_FILE = "usage.json"

      /** The root's entries that are not the state of a target. */
      val RESERVED_NAMES = setOf(INSTANCES_DIR, SNAPSHOTS_DIR, TRASH_DIR)
    }
  }

/**
 * Bazel's digest of an input, as the request carries it: computed with the build's
 * `--digest_function`. Null when the request has none for the path.
 */
private fun Map<String, String>.digestOf(path: String): String? =
  get(path)?.takeIf { it.isNotEmpty() }

private fun canonicalPath(path: String): String =
  try {
    Paths.get(path).toRealPath().toString()
  } catch (_: IOException) {
    Paths
      .get(path)
      .toAbsolutePath()
      .normalize()
      .toString()
  }

/** The stamp of every file under [dir], by relative path. */
private fun stamps(dir: Path): Map<String, FileStamp> {
  if (!Files.isDirectory(dir)) {
    return emptyMap()
  }
  return Files.walk(dir).use { paths ->
    paths
      .filter { Files.isRegularFile(it) }
      .collect(Collectors.toList())
      .associate { file ->
        dir.relativize(file).toString() to
          FileStamp.of(Files.readAttributes(file, BasicFileAttributes::class.java))
      }
  }
}

/** Identifies a version of a file: the compiler writes a new file for each version, see [begin]. */
private data class FileStamp(
  val size: Long,
  val modified: FileTime,
  val key: Any?,
) {
  companion object {
    fun of(attributes: BasicFileAttributes) =
      FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey())
  }
}

private fun entries(dir: Path): List<Path> =
  try {
    Files.list(dir).use { it.collect(Collectors.toList()) }
  } catch (_: IOException) {
    emptyList()
  }

private fun lastModified(path: Path): Instant =
  try {
    Files.getLastModifiedTime(path).toInstant()
  } catch (_: IOException) {
    Instant.now()
  }

private fun sizeOf(path: Path): Long =
  try {
    Files.walk(path).use { paths ->
      paths
        .mapToLong {
          runCatching { if (Files.isRegularFile(it)) Files.size(it) else 0L }.getOrDefault(0L)
        }.sum()
    }
  } catch (_: IOException) {
    0L
  }

/** Removes the directories under [root] without files. */
private fun removeEmptyDirectories(root: Path) {
  if (!Files.isDirectory(root)) {
    return
  }
  Files
    .walk(root)
    .use { paths ->
      paths
        .filter { it != root && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        .sorted(Comparator.reverseOrder())
        .collect(Collectors.toList())
    }.forEach { dir ->
      Files.list(dir).use { it.findAny().isPresent }.let { hasEntries ->
        if (!hasEntries) {
          Files.delete(dir)
        }
      }
    }
}

/** Copies the tree [from] to [to]. */
internal fun copyTree(
  from: Path,
  to: Path,
) {
  if (!Files.isDirectory(from)) {
    return
  }
  Files.walk(from).use { paths ->
    paths.forEach { source ->
      val target = to.resolve(from.relativize(source).toString())
      if (Files.isDirectory(source)) {
        Files.createDirectories(target)
      } else {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }
}

private fun deleteRecursively(path: Path) {
  if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
    return
  }
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
  }
}

private fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))

private fun sha256(bytes: ByteArray): String =
  MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
