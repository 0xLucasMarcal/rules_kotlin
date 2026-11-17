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
package io.bazel.kotlin.builder.tasks

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.bazel.kotlin.builder.tasks.jvm.Ksp2IncrementalOptions
import io.bazel.kotlin.builder.tasks.jvm.Ksp2IncrementalProcessing
import io.bazel.kotlin.builder.tasks.jvm.Ksp2Task
import io.bazel.kotlin.builder.tasks.jvm.Ksp2Task.Companion.clearKspClassLoaderCacheForTesting
import io.bazel.kotlin.builder.utils.BazelRunFiles
import io.bazel.worker.Status
import io.bazel.worker.TaskResult
import io.bazel.worker.WorkerContext
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Runs KSP2 with a real processor against the state of the previous request. */
@RunWith(JUnit4::class)
class Ksp2IncrementalTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val sources: Path by lazy { tmp.newFolder("sources").toPath() }
  private val outputs: Path by lazy { tmp.newFolder("outputs").toPath() }
  private val library: Path by lazy { tmp.root.toPath().resolve("lib.jar") }

  private class Processed(
    val summary: String,
    /** The generated sources, by path. */
    val generated: Map<String, String>,
    /** The run that last processed each class, by class name. */
    val runs: Map<String, String>,
  )

  @After
  fun tearDown() {
    clearKspClassLoaderCacheForTesting()
    System.clearProperty(RUN)
    System.clearProperty(FAIL)
  }

  @Test
  fun `a request without changes processes no source`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"), probe("B"))

    val first = process(1, sources)
    val second = process(2, sources)

    assertThat(first.summary).contains("full (no state), 2 sources")
    assertThat(second.summary)
      .contains("incremental, 0/2 sources modified, 0 removed, 0 changed classes in 0 libraries")
    assertThat(second.summary).contains("jars reused")
    assertThat(second.runs).containsExactly("A", "1", "B", "1")
    assertThat(second.generated).isEqualTo(first.generated)
  }

  @Test
  fun `a modified source is the only one processed again`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"), probe("B"))
    process(1, sources)

    probe("A", body = "{\n  fun added() = 0\n}")
    val second = process(2, sources)

    assertThat(second.summary).contains("incremental, 1/2 sources modified, 0 removed")
    assertThat(second.runs).containsExactly("A", "2", "B", "1")
    assertThat(second.generated["probe/AProbe.kt"]).contains("\"added\"")
    assertThat(second.generated).isEqualTo(processFromScratch(sources).generated)
  }

  @Test
  fun `a processed source loses the outputs it no longer generates`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"), probe("B"))
    process(1, sources)

    Files.write(Paths.get(sources[0]), "package app\n\nclass A\n".toByteArray())
    val second = process(2, sources)

    assertThat(second.summary).contains("incremental, 1/2 sources modified")
    assertThat(second.summary).doesNotContain("jars reused")
    assertThat(second.generated.keys).containsExactly("probe/BProbe.kt")
    assertThat(second.runs).containsExactly("B", "1")
  }

  @Test
  fun `a removed source loses its outputs`() {
    useLibrary(LIB_V1)
    val a = probe("A")
    process(1, listOf(a, probe("B")))

    val second = process(2, listOf(a))

    assertThat(second.summary).contains("incremental, 0/1 sources modified, 1 removed")
    assertThat(second.generated.keys).containsExactly("probe/AProbe.kt")
    assertThat(second.runs).containsExactly("A", "1")
  }

  @Test
  fun `a changed library class processes the sources that use it again`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A", supertype = "lib.Base"), probe("B"))
    process(1, sources)

    useLibrary(LIB_V2)
    val second = process(2, sources)

    assertThat(second.summary)
      .contains("incremental, 0/2 sources modified, 0 removed, 1 changed classes in 1 libraries")
    assertThat(second.runs).containsExactly("A", "2", "B", "1")
    assertThat(second.generated["probe/AProbe.kt"]).contains("\"two\"")
    assertThat(second.generated).isEqualTo(processFromScratch(sources).generated)
  }

  @Test
  fun `a failed request discards the state`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"))
    process(1, sources)

    System.setProperty(FAIL, "true")
    assertThat(run(2, sources, STATE).status).isEqualTo(Status.ERROR)
    System.clearProperty(FAIL)

    val third = process(3, sources)
    assertThat(third.summary).contains("full (no state)")
    assertThat(third.runs).containsExactly("A", "3")
  }

  @Test
  fun `a request that did not complete discards the state`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"))
    process(1, sources)
    Files.list(tmp.root.toPath().resolve(STATE)).use { targets ->
      targets.forEach { Files.write(it.resolve("in-progress"), ByteArray(0)) }
    }

    val second = process(2, sources)

    assertThat(second.summary).contains("full (the previous request did not complete)")
    assertThat(second.runs).containsExactly("A", "2")
  }

  @Test
  fun `another processor option processes every source`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"))
    process(1, sources)

    val second = process(2, sources, options = listOf("key=value"))

    assertThat(second.summary).contains("full (the configuration changed)")
    assertThat(second.runs).containsExactly("A", "2")
  }

  @Test
  fun `verification finds incremental outputs equal to those from scratch`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A", supertype = "lib.Base"), probe("B"))
    process(1, sources)

    useLibrary(LIB_V2)
    probe("B", body = "{\n  fun added() = 0\n}")
    val second = process(2, sources, verify = true)

    assertThat(second.runs).containsExactly("A", "2", "B", "2")
    assertThat(second.summary).contains(", verified")
  }

  @Test
  fun `verification reports the outputs that differ from processing from scratch`() {
    useLibrary(LIB_V1)
    val sources = listOf(probe("A"), probe("B"))
    process(1, sources)

    probe("B", body = "{\n  fun added() = 0\n}")
    val second = process(2, sources, verify = true)

    // The resource holds the run that processed the class: A's is the first run's.
    assertThat(second.summary)
      .contains("MISMATCH with processing from scratch: 1 outputs [resources/probe/A.run]")
  }

  @Test
  fun `the worker flags set the options`() {
    assertThat(Ksp2IncrementalOptions.fromWorkerFlags(listOf("--persistent_worker"))).isNull()
    assertThat(Ksp2IncrementalOptions.fromWorkerFlags(listOf(Ksp2IncrementalOptions.FLAG)))
      .isEqualTo(Ksp2IncrementalOptions())
    assertThat(
      Ksp2IncrementalOptions.fromWorkerFlags(
        listOf(
          Ksp2IncrementalOptions.FLAG,
          "${Ksp2IncrementalOptions.FLAG}_summary",
          "${Ksp2IncrementalOptions.FLAG}_log=true",
          "${Ksp2IncrementalOptions.FLAG}_verify",
        ),
      ),
    ).isEqualTo(Ksp2IncrementalOptions(summary = true, kspLog = true, verify = true))
    assertThat(
      Ksp2IncrementalOptions.fromWorkerFlags(listOf("${Ksp2IncrementalOptions.FLAG}=false")),
    ).isNull()
    assertThrows(IllegalArgumentException::class.java) {
      Ksp2IncrementalOptions.fromWorkerFlags(listOf("${Ksp2IncrementalOptions.FLAG}_unknown"))
    }
  }

  @Test
  fun `class entries are named the way KSP looks classes up`() {
    assertThat(Ksp2IncrementalProcessing.className("a/b/C.class")).isEqualTo("a.b.C")
    assertThat(Ksp2IncrementalProcessing.className("a/b/C\$D.class")).isEqualTo("a.b.C.D")
    assertThat(Ksp2IncrementalProcessing.className("META-INF/versions/11/a/C.class"))
      .isEqualTo("a.C")
    assertThat(Ksp2IncrementalProcessing.className("a/b/c.txt")).isNull()
  }

  private fun useLibrary(version: String) {
    Files.copy(runfile(version), library, StandardCopyOption.REPLACE_EXISTING)
  }

  /** Writes the source of the class [name], annotated with `probe.Probe`, and returns its path. */
  private fun probe(
    name: String,
    supertype: String? = null,
    body: String = "",
  ): String {
    val path = sources.resolve("$name.kt")
    val extends = supertype?.let { " : $it()" }.orEmpty()
    Files.write(path, "package app\n\n@probe.Probe\nclass $name$extends $body\n".toByteArray())
    return path.toString()
  }

  private fun process(
    run: Int,
    sources: List<String>,
    options: List<String> = emptyList(),
    verify: Boolean = false,
  ): Processed = processed(run(run, sources, STATE, options, verify))

  /** Processes the [sources] against a state of their own, from scratch. */
  private fun processFromScratch(sources: List<String>): Processed =
    processed(run(0, sources, "scratch-${System.nanoTime()}"))

  private fun processed(result: TaskResult): Processed {
    assertWithMessage(result.log.toString()).that(result.status).isEqualTo(Status.SUCCESS)
    return Processed(
      summary =
        result.log
          .lines()
          .single { it.startsWith("ksp2 incremental processing:") },
      generated = entries(outputs.resolve("gensrc.jar")),
      runs =
        entries(outputs.resolve("genclasses.jar"))
          .filterKeys { it.endsWith(".run") }
          .mapKeys { (name, _) -> name.removePrefix("probe/").removeSuffix(".run") },
    )
  }

  private fun run(
    run: Int,
    sources: List<String>,
    state: String,
    options: List<String> = emptyList(),
    verify: Boolean = false,
  ): TaskResult {
    System.setProperty(RUN, run.toString())
    val libraries = listOf(runfile(KOTLIN_STDLIB).toString(), library.toString())
    val processorClasspath = PROCESSOR_CLASSPATH.map { runfile(it).toString() }
    val args =
      buildList {
        add("--module_name")
        add("app")
        add("--sources")
        addAll(sources)
        add("--libraries")
        addAll(libraries)
        add("--processor_classpath")
        addAll(processorClasspath)
        add("--generated_sources_output")
        add(outputs.resolve("gensrc.jar").toString())
        add("--generated_classes_output")
        add(outputs.resolve("genclasses.jar").toString())
        add("--jdk_home")
        add(System.getProperty("java.home"))
        add("--jvm_target")
        add("11")
        add("--language_version")
        add("2.1")
        add("--api_version")
        add("2.1")
        options.forEach {
          add("--ksp_options")
          add(it)
        }
      }
    val digests = (sources + libraries + processorClasspath).associateWith { sha256(Paths.get(it)) }
    val task =
      Ksp2Task(
        Ksp2IncrementalOptions(summary = true, verify = verify),
        tmp.root.toPath().resolve(state),
      )
    return WorkerContext.run(named = "test") {
      doTask("ksp2", sandboxDir = tmp.root.toPath(), inputDigests = digests) { context ->
        task.invoke(context, args)
      }
    }
  }

  private fun entries(jar: Path): Map<String, String> =
    ZipFile(jar.toFile()).use { zip ->
      zip
        .entries()
        .asSequence()
        .filterNot { it.isDirectory || it.name.startsWith("META-INF/") }
        .associate { it.name to zip.getInputStream(it).use { input -> String(input.readBytes()) } }
    }

  private fun sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .joinToString("") { "%02x".format(it) }

  private companion object {
    const val RUN = "ksp.probe.run"
    const val FAIL = "ksp.probe.fail"
    const val STATE = "state"
    const val KOTLIN_STDLIB = "//kotlin/compiler:kotlin-stdlib"
    const val LIB_V1 = "//src/test/data/jvm/ksp/incremental:probe_lib_v1.jar"
    const val LIB_V2 = "//src/test/data/jvm/ksp/incremental:probe_lib_v2.jar"
    val PROCESSOR_CLASSPATH =
      listOf(
        "//src/main/kotlin/io/bazel/kotlin/ksp2:ksp2.jar",
        "@com_github_google_ksp//:symbol-processing-api.jar",
        "@com_github_google_ksp//:symbol-processing-aa.jar",
        "@com_github_google_ksp//:symbol-processing-common-deps.jar",
        "@com_github_google_ksp//:kotlinx-coroutines-core-jvm-intellij.jar",
        "//src/test/data/jvm/ksp/incremental:probe_processor.jar",
      )

    fun runfile(label: String): Path =
      BazelRunFiles
        .resolveVerifiedFromProperty(label.replace('/', '.').replace(':', '.'))
        .toPath()
  }
}
