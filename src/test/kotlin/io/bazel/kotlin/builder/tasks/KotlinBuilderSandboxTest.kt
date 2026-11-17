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
import com.google.devtools.build.lib.view.proto.Deps
import com.google.devtools.build.lib.view.proto.Deps.Dependency.Kind
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.fingerprintOf
import io.bazel.kotlin.model.CompilationTaskInfo
import io.bazel.kotlin.model.JvmCompilationTask
import io.bazel.worker.RequestPaths
import io.bazel.worker.Status
import io.bazel.worker.WorkerContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A request under multiplex sandboxing: Bazel names its files relative to the execution root and
 * stages them in the request's sandbox_dir.
 */
@RunWith(JUnit4::class)
class KotlinBuilderSandboxTest {
  @get:Rule
  val tmp = TemporaryFolder()

  private val flagfile = "bazel-out/bin/lib-kt.jar-0.params"
  private val jdeps = "bazel-out/bin/lib-kt.jdeps"

  private val request =
    listOf(
      "--target_label",
      "//pkg:lib",
      "--classpath",
      "bazel-out/bin/dep.abi.jar",
      "--direct_dependencies",
      "bazel-out/bin/dep.abi.jar",
      "--deps_artifacts",
      "bazel-out/bin/dep.jdeps",
      "--sources",
      "pkg/A.kt",
      "--source_jars",
      "bazel-out/bin/gen.srcjar",
      "--processorpath",
      "external/ap/ap.jar",
      "--processors",
      "ap.Processor",
      "--output",
      "bazel-out/bin/lib-kt.jar",
      "--kotlin_output_jdeps",
      jdeps,
      "--kotlin_friend_paths",
      "bazel-out/bin/friend.jar",
      "--rule_kind",
      "kt_jvm_library",
      "--kotlin_module_name",
      "lib",
      "--kotlin_api_version",
      "2.0",
      "--kotlin_language_version",
      "2.0",
      "--kotlin_jvm_target",
      "11",
      "--kotlin_debug_tags",
      "--build_kotlin",
      "true",
      "--strict_kotlin_deps",
      "off",
      "--reduced_classpath_mode",
      "off",
      "--instrument_coverage",
      "false",
      "--plugins_payload",
      // One line: each line of the flagfile is an argument.
      """{"plugins": [{"id": "p", "classpath": ["external/p/p.jar"], """ +
        """"phases": ["PLUGIN_PHASE_COMPILE"]}]}""",
      "--btapi_impl_classpath",
      "external/btapi/impl.jar",
      "external/btapi/compiler.jar",
      "--internal_jvm_abi_gen_classpath",
      "external/btapi/abi.jar",
      "--internal_kapt_classpath",
      "external/btapi/kapt.jar",
      "--internal_jdeps_gen_classpath",
      "external/btapi/jdeps.jar",
    )

  private var task: JvmCompilationTask? = null
  private val capturing =
    object : JvmTaskExecutor {
      override fun execute(
        context: CompilationTaskContext,
        task: JvmCompilationTask,
      ) {
        this@KotlinBuilderSandboxTest.task = task
      }
    }

  /** Builds the request in [sandbox], where it stages the request's flagfile as Bazel does. */
  private fun buildInSandbox(
    sandbox: Path = tmp.newFolder().toPath(),
    executor: JvmTaskExecutor = capturing,
    inputDigests: Map<String, String> = emptyMap(),
  ): Path {
    Files.createDirectories(sandbox.resolve(flagfile).parent)
    Files.write(sandbox.resolve(flagfile), request)
    var status = -1
    WorkerContext.run(named = "test") {
      doTask("build", sandboxDir = sandbox, inputDigests = inputDigests) { ctx ->
        status = KotlinBuilder(executor).build(ctx, listOf("--flagfile=$flagfile"))
        Status.SUCCESS
      }
    }
    assertThat(status).isEqualTo(0)
    return sandbox
  }

  /** The path of [path] in this sandbox, as the worker resolves the request's paths. */
  private fun Path.file(path: String): String = RequestPaths.sandboxed(this).resolve(path)

  @Test
  fun `the task reads and writes the request's files in the sandbox`() {
    val sandbox = buildInSandbox()
    val task = checkNotNull(task) { "the task never reached the executor" }

    with(task.inputs) {
      assertThat(classpathList).containsExactly(sandbox.file("bazel-out/bin/dep.abi.jar"))
      assertThat(directDependenciesList).containsExactly(sandbox.file("bazel-out/bin/dep.abi.jar"))
      assertThat(depsArtifactsList).containsExactly(sandbox.file("bazel-out/bin/dep.jdeps"))
      assertThat(kotlinSourcesList).containsExactly(sandbox.file("pkg/A.kt"))
      assertThat(sourceJarsList).containsExactly(sandbox.file("bazel-out/bin/gen.srcjar"))
      assertThat(processorpathsList).containsExactly(sandbox.file("external/ap/ap.jar"))
      assertThat(processorsList).containsExactly("ap.Processor")
      assertThat(pluginsList.single().classpathList)
        .containsExactly(sandbox.file("external/p/p.jar"))
      assertThat(compilerPluginClasspathList).containsExactly(sandbox.file("external/p/p.jar"))
    }
    assertThat(task.outputs.jar).isEqualTo(sandbox.file("bazel-out/bin/lib-kt.jar"))
    assertThat(task.outputs.jdeps).isEqualTo(sandbox.file(jdeps))
    assertThat(task.info.friendPathsList).containsExactly(sandbox.file("bazel-out/bin/friend.jar"))
    assertThat(task.info.label).isEqualTo("//pkg:lib")
    assertThat(task.info.moduleName).isEqualTo("lib")
    with(task.info.toolchainInfo.btapi) {
      assertThat(apiImplClasspathList)
        .containsExactly(
          sandbox.file("external/btapi/impl.jar"),
          sandbox.file("external/btapi/compiler.jar"),
        ).inOrder()
      assertThat(jvmAbiGenClasspathList).containsExactly(sandbox.file("external/btapi/abi.jar"))
      assertThat(kaptClasspathList).containsExactly(sandbox.file("external/btapi/kapt.jar"))
      assertThat(jdepsGenClasspathList).containsExactly(sandbox.file("external/btapi/jdeps.jar"))
    }
    assertThat(Files.isDirectory(Paths.get(task.directories.classes))).isTrue()
    assertThat(Paths.get(task.directories.classes).startsWith(sandbox)).isTrue()
  }

  @Test
  fun `the runtime fingerprint is the same in every sandbox`() {
    // Bazel names the digests by the paths of the request.
    val digests = mapOf("external/btapi/impl.jar" to "d1", "external/btapi/compiler.jar" to "d2")
    val fingerprints =
      (1..2).map {
        buildInSandbox(inputDigests = digests)
        checkNotNull(task).info.toolchainInfo.btapi.classpathFingerprint
      }
    val expected =
      fingerprintOf(listOf("external/btapi/impl.jar", "external/btapi/compiler.jar"), digests)
    assertThat(expected).isNotEmpty()
    assertThat(fingerprints).containsExactly(expected, expected)
  }

  @Test
  fun `the jdeps name the jars the way the request does`() {
    val sandbox = tmp.newFolder().toPath()
    val outside = tmp.root.toPath().resolve("jdk").resolve("rt.jar").toString()
    val writingJdeps =
      object : JvmTaskExecutor {
        override fun execute(
          context: CompilationTaskContext,
          task: JvmCompilationTask,
        ) {
          // The jdeps plugin records the classpath entries the compiler read.
          val recorded =
            Deps.Dependencies
              .newBuilder()
              .setRuleLabel(task.info.label)
              .addDependency(dependency(sandbox.file("bazel-out/bin/z.jar"), Kind.EXPLICIT))
              .addDependency(dependency(sandbox.file("bazel-out/bin/a.jar"), Kind.UNUSED))
              .addDependency(dependency(outside, Kind.IMPLICIT))
              .build()
          Files.newOutputStream(Paths.get(task.outputs.jdeps)).use(recorded::writeTo)
        }
      }
    buildInSandbox(sandbox, writingJdeps)

    val written = Files.newInputStream(sandbox.resolve(jdeps)).use(Deps.Dependencies::parseFrom)
    assertThat(written.ruleLabel).isEqualTo("//pkg:lib")
    assertThat(written.dependencyList.map { it.path })
      .containsExactlyElementsIn(
        listOf("bazel-out/bin/a.jar", "bazel-out/bin/z.jar", outside).sorted(),
      ).inOrder()
    assertThat(written.dependencyList.associate { it.path to it.kind })
      .containsEntry("bazel-out/bin/z.jar", Kind.EXPLICIT)
  }

  @Test
  fun `compiler diagnostics name the sources the way the request does`() {
    val sandbox = tmp.newFolder().toPath()
    val printed = ByteArrayOutputStream()
    val context =
      CompilationTaskContext(
        CompilationTaskInfo.getDefaultInstance(),
        PrintStream(printed, true),
        paths = RequestPaths.sandboxed(sandbox),
      )
    val diagnostic = "${sandbox.file("pkg/A.kt")}:1:1: error: unresolved reference"

    val failing = { out: PrintStream ->
      out.println(diagnostic)
      1
    }
    val expected = "${Paths.get("pkg", "A.kt")}:1:1: error: unresolved reference"

    // The failure carries the output to its handler, or the context prints it.
    val thrown =
      runCatching { context.executeCompilerTask(failing, printOnFail = false) }
        .exceptionOrNull() as CompilationStatusException
    assertThat(thrown.lines).containsExactly(expected)
    runCatching { context.executeCompilerTask(failing) }
    assertThat(printed.toString()).contains(expected)
    assertThat(printed.toString()).doesNotContain(sandbox.toString())
  }

  private fun dependency(
    path: String,
    kind: Kind,
  ): Deps.Dependency =
    Deps.Dependency
      .newBuilder()
      .setPath(path)
      .setKind(kind)
      .build()
}
