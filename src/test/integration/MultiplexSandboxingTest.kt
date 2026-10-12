package io.bazel.kotlin.integration

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.test.BazelIntegrationTestBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The worker actions under multiplex sandboxing through a real Bazel: each work request names its
 * files relative to the execution root, while Bazel stages them in the request's sandbox_dir.
 */
class MultiplexSandboxingTest : BazelIntegrationTestBase() {
  // JUnit creates an instance per test: the tests share the release, the consumer and its output.
  private val release by lazy {
    root.resolve("rules_kotlin").takeIf { Files.exists(it.resolve("MODULE.bazel")) }
      ?: unpackRelease(env("RULES_KOTLIN_RELEASE"))
  }
  private val output by lazy { root.resolve("output") }
  private val consumer: Path by lazy {
    root.resolve("consumer").createDirectories().also { consumer ->
      consumer.resolve("MODULE.bazel").writeText(
        """
        module(name = "sandboxing_consumer")
        bazel_dep(name = "rules_java", version = "8.9.0")
        bazel_dep(name = "rules_kotlin", version = "2.2.0")
        """.trimIndent(),
      )
      consumer.resolve("BUILD.bazel").writeText(
        """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain", "kt_ksp_plugin")
        load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")

        define_kt_toolchain(name = "sandboxed", experimental_multiplex_sandboxing = True)
        define_kt_toolchain(
            name = "path_mapped",
            experimental_multiplex_sandboxing = True,
            supports_path_mapping = True,
        )
        define_kt_toolchain(
            name = "unused_deps_reported",
            experimental_multiplex_sandboxing = True,
            experimental_report_unused_deps = "warn",
        )

        kt_jvm_library(name = "base", srcs = ["Base.kt"])
        kt_jvm_library(name = "unused", srcs = ["Unused.kt"])
        kt_jvm_library(name = "lib", srcs = ["A.kt"], deps = [":base", ":unused"])
        kt_jvm_library(name = "bad", srcs = ["Bad.kt"], deps = [":base"])

        java_library(name = "gen", srcs = ["proc/Gen.java"])
        java_plugin(
            name = "gen_processor",
            srcs = ["proc/GenProcessor.java"],
            processor_class = "proc.GenProcessor",
            deps = [":gen"],
        )
        kt_jvm_library(
            name = "kapt",
            srcs = ["K.kt"],
            plugins = [":gen_processor"],
            deps = [":gen"],
        )

        kt_jvm_library(name = "kgen", srcs = ["kp/KGen.kt"])
        kt_jvm_library(
            name = "kgen_processor_lib",
            srcs = ["kp/GenProvider.kt"],
            resource_strip_prefix = "kp/resources",
            resources = ["$KSP_SERVICES"],
            deps = [":kgen", "@rules_kotlin//kotlin/compiler:symbol-processing-api"],
        )
        kt_ksp_plugin(
            name = "kgen_processor",
            processor_class = "kp.GenProvider",
            deps = [":kgen_processor_lib"],
        )
        kt_jvm_library(
            name = "ksp",
            srcs = ["Ksp.kt"],
            plugins = [":kgen_processor"],
            deps = [":kgen"],
        )
        """.trimIndent(),
      )
      consumer.resolve("Base.kt").writeText("package sb\nfun base(): Int = 0\n")
      consumer.resolve("Unused.kt").writeText("package sb\nfun unused(): Int = 0\n")
      consumer.resolve("A.kt").writeText("package sb\nfun a(): Int = base() + 1\n")
      consumer.resolve("Bad.kt").writeText("package sb\nfun bad(): Int = base() + \"x\"\n")
      // Each refers to the class its annotation processor generates.
      consumer.resolve("K.kt").writeText("package sb\n@proc.Gen\nclass Foo\nval f = FooGen()\n")
      consumer.resolve("Ksp.kt").writeText("package sb\n@kp.KGen\nclass Bar\nval b = BarKGen()\n")
      consumer.resolve("proc").createDirectories()
      consumer.resolve("proc/Gen.java").writeText(
        """
        package proc;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Target;

        @Target(ElementType.TYPE)
        public @interface Gen {}
        """.trimIndent(),
      )
      consumer.resolve("proc/GenProcessor.java").writeText(
        """
        package proc;

        import java.io.IOException;
        import java.io.Writer;
        import java.util.Collections;
        import java.util.Set;
        import javax.annotation.processing.AbstractProcessor;
        import javax.annotation.processing.RoundEnvironment;
        import javax.lang.model.SourceVersion;
        import javax.lang.model.element.Element;
        import javax.lang.model.element.TypeElement;

        /** Generates a {@code <Name>Gen} class next to each class annotated with {@link Gen}. */
        public final class GenProcessor extends AbstractProcessor {
          @Override
          public Set<String> getSupportedAnnotationTypes() {
            return Collections.singleton(Gen.class.getName());
          }

          @Override
          public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
          }

          @Override
          public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
            for (Element element : env.getElementsAnnotatedWith(Gen.class)) {
              String pkg =
                  processingEnv.getElementUtils().getPackageOf(element).getQualifiedName()
                      .toString();
              String name = element.getSimpleName() + "Gen";
              try (Writer writer =
                  processingEnv.getFiler().createSourceFile(pkg + "." + name, element)
                      .openWriter()) {
                writer.write("package " + pkg + ";\npublic final class " + name + " {}\n");
              } catch (IOException e) {
                throw new IllegalStateException(e);
              }
            }
            return true;
          }
        }
        """.trimIndent(),
      )
      consumer.resolve("kp").createDirectories()
      consumer.resolve("kp/KGen.kt").writeText("package kp\n\nannotation class KGen\n")
      consumer.resolve("kp/GenProvider.kt").writeText(
        """
        package kp

        import com.google.devtools.ksp.processing.Dependencies
        import com.google.devtools.ksp.processing.Resolver
        import com.google.devtools.ksp.processing.SymbolProcessor
        import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
        import com.google.devtools.ksp.processing.SymbolProcessorProvider
        import com.google.devtools.ksp.symbol.KSAnnotated
        import com.google.devtools.ksp.symbol.KSClassDeclaration

        /** Generates a `<Name>KGen` class next to each class annotated with [KGen]. */
        class GenProvider : SymbolProcessorProvider {
          override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
            object : SymbolProcessor {
              override fun process(resolver: Resolver): List<KSAnnotated> {
                resolver
                  .getSymbolsWithAnnotation(KGen::class.qualifiedName!!)
                  .filterIsInstance<KSClassDeclaration>()
                  .forEach { declaration ->
                    val pkg = declaration.packageName.asString()
                    val name = declaration.simpleName.asString() + "KGen"
                    environment.codeGenerator
                      .createNewFile(Dependencies(false, declaration.containingFile!!), pkg, name)
                      .use { it.write("package ${'$'}pkg\n\nclass ${'$'}name\n".toByteArray()) }
                  }
                return emptyList()
              }
            }
        }
        """.trimIndent(),
      )
      consumer
        .resolve(KSP_SERVICES)
        .also { it.parent.createDirectories() }
        .writeText("kp.GenProvider\n")
    }
  }

  /** Builds with multiplex sandboxing; in batch mode, the build starts its own workers. */
  private fun build(vararg args: String): Result<ProcessResult> =
    bazel.run(
      consumer,
      "--batch",
      "--ignore_all_rc_files",
      "--host_jvm_args=-Djava.net.preferIPv6Addresses=system",
      "--output_user_root=${root.resolve("bazel")}",
      "--output_base=$output",
      "build",
      "--noshow_progress",
      "--color=no",
      "--override_module=rules_kotlin=$release",
      // rules_kotlin's own dependencies may resolve rules_java to a newer version.
      "--check_direct_dependencies=off",
      "--experimental_worker_multiplex_sandboxing",
      // Reports the workers the build starts.
      "--worker_verbose",
      *args,
    )

  private val ByteArray.utf8: String get() = toString(UTF_8)

  private val ProcessResult.lines: List<String>
    get() = (stdOut + stdErr).utf8.lines()

  /** The mnemonics of the sandboxed multiplex workers the build started. */
  private val ProcessResult.sandboxedMnemonics: Set<String>
    get() = lines.mapNotNull { SANDBOXED_WORKER.find(it)?.groupValues?.get(1) }.toSet()

  private fun bin(path: String): Path = consumer.resolve("bazel-bin").toRealPath().resolve(path)

  private fun jdepsPaths(path: String): List<String> =
    Files.newInputStream(bin(path)).use(Deps.Dependencies::parseFrom).dependencyList.map { it.path }

  private fun entries(jar: String): Map<String, ByteArray> =
    ZipFile(bin(jar).toFile()).use { zip ->
      zip.entries().toList().associate { it.name to zip.getInputStream(it).readBytes() }
    }

  /** The output files, and the entries of the output jars, that name a sandbox. */
  private fun outputsNamingASandbox(): List<String> {
    val outputs =
      Files.walk(consumer.resolve("bazel-bin").toRealPath()).use { files ->
        files
          .filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
          .collect(Collectors.toList())
      }
    return outputs.flatMap { file ->
      if (file.fileName.toString().endsWith(".jar")) {
        ZipFile(file.toFile()).use { zip ->
          zip
            .entries()
            .toList()
            .filter { SANDBOX in it.name || SANDBOX in zip.getInputStream(it).readBytes().utf8 }
            .map { "$file!${it.name}" }
        }
      } else {
        listOfNotNull(file.toString().takeIf { SANDBOX in Files.readAllBytes(file).utf8 })
      }
    }
  }

  @Test
  fun workerActionsCompileInMultiplexSandboxes() {
    for (buildToolsApi in listOf(false, true)) {
      val result =
        build(
          "--extra_toolchains=//:sandboxed",
          "--@rules_kotlin//kotlin/settings:experimental_build_tools_api=$buildToolsApi",
          // Instruments the classes, which records their sources in the output jar.
          "--collect_code_coverage",
          "--instrumentation_filter=//...",
          "//:lib",
          "//:kapt",
          "//:ksp",
        ).getOrThrow()
      val context = "Build Tools API: $buildToolsApi\n${result.lines.joinToString("\n")}"

      // The KSP and jdeps merging actions do not depend on the compiler runtime: the second build
      // finds them in the action cache.
      val executed =
        if (buildToolsApi) {
          listOf("KotlinCompile", "KotlinKapt")
        } else {
          listOf("KotlinCompile", "KotlinKapt", "KotlinKsp2", "JdepsMerge")
        }
      assertTrue(context, result.sandboxedMnemonics.containsAll(executed))
      assertEquals(context, emptyList<String>(), outputsNamingASandbox())
      for (jdeps in listOf("lib-kt.jdeps", "lib.jdeps")) {
        val paths = jdepsPaths(jdeps)
        assertTrue("$jdeps: $paths", paths.any { BASE_ABI_JAR.matches(it) })
        assertTrue("$jdeps: $paths", paths.none { it.startsWith("/") })
      }
      assertEquals(
        context,
        "A.kt",
        entries("lib-kt.jar").getValue("lib-kt.jar-paths-for-coverage.txt").utf8.trim(),
      )
      assertTrue(context, "sb/FooGen.class" in entries("kapt.jar"))
      assertTrue(context, "sb/BarKGen.class" in entries("ksp.jar"))
    }
  }

  @Test
  fun pathMappingRunsInMultiplexSandboxes() {
    val result =
      build(
        "--extra_toolchains=//:path_mapped",
        "--experimental_output_paths=strip",
        "//:lib",
      ).getOrThrow()

    assertTrue(result.lines.joinToString("\n"), "KotlinCompile" in result.sandboxedMnemonics)
    val paths = jdepsPaths("lib.jdeps")
    // The mapped path of the dependency shows that the action ran path-mapped.
    assertTrue(paths.toString(), "bazel-out/cfg/bin/base.abi.jar" in paths)
    assertEquals(emptyList<String>(), outputsNamingASandbox())
  }

  @Test
  fun unusedDependenciesAreReportedFromMultiplexSandboxes() {
    val result = build("--extra_toolchains=//:unused_deps_reported", "//:lib").getOrThrow()

    assertTrue(result.lines.joinToString("\n"), "JdepsMerge" in result.sandboxedMnemonics)
    assertTrue(
      result.lines.joinToString("\n"),
      result.lines.any { "buildozer 'remove deps @//:unused' //:lib" in it },
    )
  }

  @Test
  fun compilationErrorsNameTheSources() {
    for (buildToolsApi in listOf(false, true)) {
      val failure =
        build(
          "--extra_toolchains=//:sandboxed",
          "--@rules_kotlin//kotlin/settings:experimental_build_tools_api=$buildToolsApi",
          "//:bad",
        ).exceptionOrNull()
      val message = checkNotNull(failure) { "//:bad built" }.message.orEmpty()

      assertTrue(message, message.lines().any { it.trimStart().startsWith("Bad.kt:2:") })
      assertFalse(message, SANDBOX in message)
    }
  }

  private companion object {
    const val SANDBOX = "__sandbox"
    const val KSP_SERVICES =
      "kp/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
    val BASE_ABI_JAR = Regex("bazel-out/[^/]+/bin/base\\.abi\\.jar")

    /** How --worker_verbose reports a sandboxed multiplex worker. */
    val SANDBOXED_WORKER = Regex("Created new sandboxed multiplex (\\S+) multiplex-worker")
  }
}
