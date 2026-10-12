/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.view.proto.Deps
import io.bazel.kotlin.builder.toolchain.CompilationStatusException
import io.bazel.kotlin.builder.toolchain.CompilationTaskContext
import io.bazel.kotlin.builder.utils.ArgMap
import io.bazel.kotlin.builder.utils.ArgMaps
import io.bazel.kotlin.builder.utils.Flag
import io.bazel.kotlin.builder.utils.fingerprintOf
import io.bazel.kotlin.builder.utils.partitionJvmSources
import io.bazel.kotlin.builder.utils.resolveNewDirectories
import io.bazel.kotlin.model.CompilationTaskInfo
import io.bazel.kotlin.model.JvmCompilationTask
import io.bazel.kotlin.model.KotlinToolchainInfo
import io.bazel.kotlin.model.Platform
import io.bazel.kotlin.model.RuleKind
import io.bazel.worker.RequestPaths
import io.bazel.worker.WorkerContext
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern

@Suppress("MemberVisibilityCanBePrivate")
class KotlinBuilder(
  private val jvmTaskExecutor: JvmTaskExecutor,
) {
  companion object {
    @JvmStatic
    private val FLAGFILE_RE = Pattern.compile("""^--flagfile=((.*)-(\d+).params)$""").toRegex()

    enum class KotlinBuilderFlags(
      override val flag: String,
      /** Whether the values are paths of the work request, see [RequestPaths]. */
      val isPath: Boolean = false,
    ) : Flag {
      TARGET_LABEL("--target_label"),
      CLASSPATH("--classpath", isPath = true),
      DIRECT_DEPENDENCIES("--direct_dependencies", isPath = true),
      DEPS_ARTIFACTS("--deps_artifacts", isPath = true),
      SOURCES("--sources", isPath = true),
      SOURCE_JARS("--source_jars", isPath = true),
      PROCESSOR_PATH("--processorpath", isPath = true),
      PROCESSORS("--processors"),

      // A JSON value: the plugin classpaths in it are resolved when it is parsed.
      PLUGINS_PAYLOAD("--plugins_payload"),
      OUTPUT("--output", isPath = true),
      RULE_KIND("--rule_kind"),
      MODULE_NAME("--kotlin_module_name"),
      PASSTHROUGH_FLAGS("--kotlin_passthrough_flags"),
      API_VERSION("--kotlin_api_version"),
      LANGUAGE_VERSION("--kotlin_language_version"),
      JVM_TARGET("--kotlin_jvm_target"),
      OUTPUT_SRCJAR("--kotlin_output_srcjar", isPath = true),
      GENERATED_CLASSDIR("--kotlin_generated_classdir", isPath = true),
      FRIEND_PATHS("--kotlin_friend_paths", isPath = true),
      OUTPUT_JDEPS("--kotlin_output_jdeps", isPath = true),
      DEBUG("--kotlin_debug_tags"),
      TASK_ID("--kotlin_task_id"),
      ABI_JAR("--abi_jar", isPath = true),
      ABI_JAR_INTERNAL_AS_PRIVATE("--treat_internal_as_private_in_abi_jar"),
      ABI_JAR_REMOVE_PRIVATE_CLASSES("--remove_private_classes_in_abi_jar"),
      ABI_JAR_REMOVE_DEBUG_INFO("--remove_debug_info_in_abi_jar"),
      ABI_JAR_PRESERVE_DECLARATION_ORDER("--preserve_declaration_order"),
      ABI_JAR_REMOVE_DATA_CLASS_COPY_IF_CONSTRUCTOR_IS_PRIVATE(
        "--remove_data_class_copy_if_constructor_is_private",
      ),
      GENERATED_JAVA_SRC_JAR("--generated_java_srcjar", isPath = true),
      GENERATED_JAVA_STUB_JAR("--kapt_generated_stub_jar", isPath = true),
      GENERATED_CLASS_JAR("--kapt_generated_class_jar", isPath = true),
      BUILD_KOTLIN("--build_kotlin"),
      STRICT_KOTLIN_DEPS("--strict_kotlin_deps"),
      REDUCED_CLASSPATH_MODE("--reduced_classpath_mode"),
      INSTRUMENT_COVERAGE("--instrument_coverage"),
      BTAPI_IMPL_CLASSPATH("--btapi_impl_classpath", isPath = true),
      INTERNAL_JVM_ABI_GEN_CLASSPATH("--internal_jvm_abi_gen_classpath", isPath = true),
      INTERNAL_KAPT_CLASSPATH("--internal_kapt_classpath", isPath = true),
      INTERNAL_JDEPS_GEN_CLASSPATH("--internal_jdeps_gen_classpath", isPath = true),
    }

    private val PATH_FLAGS = KotlinBuilderFlags.values().filter { it.isPath }
  }

  fun build(
    taskContext: WorkerContext.TaskContext,
    args: List<String>,
  ): Int {
    val (argMap, compileContext) = buildContext(taskContext, args)
    var success = false
    var status = 0
    try {
      @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
      when (compileContext.info.platform) {
        Platform.JVM,
        Platform.ANDROID,
        -> executeJvmTask(compileContext, taskContext.directory, argMap, taskContext.paths)

        Platform.UNRECOGNIZED -> throw IllegalStateException(
          "unrecognized platform: ${compileContext.info}",
        )
      }
      success = true
    } catch (ex: CompilationStatusException) {
      taskContext.error { "Compilation failure: ${ex.message}" }
      status = ex.status
    } catch (throwable: Throwable) {
      taskContext.error(throwable) { "Uncaught exception" }
      status = 1
    } finally {
      compileContext.finalize(success)
    }
    return status
  }

  private fun buildContext(
    ctx: WorkerContext.TaskContext,
    args: List<String>,
  ): Pair<ArgMap, CompilationTaskContext> {
    check(args.isNotEmpty()) { "expected at least a single arg got: ${args.joinToString(" ")}" }
    val lines =
      FLAGFILE_RE.matchEntire(args[0])?.groups?.get(1)?.let {
        Files.readAllLines(
          FileSystems.getDefault().getPath(ctx.paths.resolve(it.value)),
          StandardCharsets.UTF_8,
        )
      } ?: args

    val requestArgs = ArgMaps.from(lines)
    // The task reads and writes the request's files where the worker finds them.
    val argMap = requestArgs.mapValues(PATH_FLAGS, ctx.paths::resolve)
    val info = buildTaskInfo(argMap, requestArgs, ctx.inputDigests).build()
    val context =
      CompilationTaskContext(info, ctx.asPrintStream(), paths = ctx.paths)
    return Pair(argMap, context)
  }

  /**
   * @param requestArgs the arguments as the request names them, before the paths are resolved.
   */
  private fun buildTaskInfo(
    argMap: ArgMap,
    requestArgs: ArgMap,
    inputDigests: Map<String, String>,
  ): CompilationTaskInfo.Builder =
    with(CompilationTaskInfo.newBuilder()) {
      addAllDebug(argMap.mandatory(KotlinBuilderFlags.DEBUG))

      label = argMap.mandatorySingle(KotlinBuilderFlags.TARGET_LABEL)
      argMap.mandatorySingle(KotlinBuilderFlags.RULE_KIND).also {
        val splitRuleKind = it.split("_")
        require(splitRuleKind[0] == "kt") { "Invalid rule kind $it" }
        platform = Platform.valueOf(splitRuleKind[1].uppercase())
        ruleKind = RuleKind.valueOf(splitRuleKind.last().uppercase())
      }
      moduleName =
        argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME).also {
          check(it.isNotBlank()) { "--kotlin_module_name should not be blank" }
        }
      addAllPassthroughFlags(argMap.optional(KotlinBuilderFlags.PASSTHROUGH_FLAGS) ?: emptyList())

      argMap.optional(KotlinBuilderFlags.FRIEND_PATHS)?.let(::addAllFriendPaths)
      toolchainInfoBuilder.commonBuilder.apiVersion =
        argMap.mandatorySingle(KotlinBuilderFlags.API_VERSION)
      toolchainInfoBuilder.commonBuilder.languageVersion =
        argMap.mandatorySingle(KotlinBuilderFlags.LANGUAGE_VERSION)
      buildBtapiRuntime(argMap, requestArgs, toolchainInfoBuilder, inputDigests)
      strictKotlinDeps = argMap.mandatorySingle(KotlinBuilderFlags.STRICT_KOTLIN_DEPS)
      reducedClasspathMode = argMap.mandatorySingle(KotlinBuilderFlags.REDUCED_CLASSPATH_MODE)
      argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_INTERNAL_AS_PRIVATE)?.let {
        treatInternalAsPrivateInAbiJar = it == "true"
      }
      argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_REMOVE_PRIVATE_CLASSES)?.let {
        removePrivateClassesInAbiJar = it == "true"
      }
      argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_REMOVE_DEBUG_INFO)?.let {
        removeDebugInfo = it == "true"
      }
      argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR_PRESERVE_DECLARATION_ORDER)?.let {
        preserveDeclarationOrder = it == "true"
      }
      argMap
        .optionalSingle(
          KotlinBuilderFlags.ABI_JAR_REMOVE_DATA_CLASS_COPY_IF_CONSTRUCTOR_IS_PRIVATE,
        )?.let {
          removeDataClassCopyIfConstructorIsPrivate = it == "true"
        }
      this
    }

  private fun executeJvmTask(
    context: CompilationTaskContext,
    workingDir: Path,
    argMap: ArgMap,
    paths: RequestPaths,
  ) {
    val task = buildJvmTask(context.info, workingDir, argMap, paths)
    context.whenTracing {
      printProto("jvm task message:", task)
    }
    jvmTaskExecutor.execute(context, task)
    if (paths.isSandboxed && task.outputs.jdeps.isNotEmpty()) {
      relativizeJdeps(Paths.get(task.outputs.jdeps), paths)
    }
  }

  /**
   * The jdeps plugin records the classpath entries the compiler read, which are resolved to the
   * request's sandbox; downstream actions name them by the paths the request names.
   */
  private fun relativizeJdeps(
    jdeps: Path,
    paths: RequestPaths,
  ) {
    if (!Files.exists(jdeps)) {
      return
    }
    val recorded = Files.newInputStream(jdeps).use(Deps.Dependencies::parseFrom)
    val relativized =
      recorded
        .toBuilder()
        .clearDependency()
        .addAllDependency(
          recorded.dependencyList
            .map { it.toBuilder().setPath(paths.relativize(it.path)).build() }
            .sortedBy { it.path },
        ).build()
    if (relativized != recorded) {
      Files.newOutputStream(jdeps).use(relativized::writeTo)
    }
  }

  private val btapiRuntimeFlags =
    listOf(
      KotlinBuilderFlags.BTAPI_IMPL_CLASSPATH,
      KotlinBuilderFlags.INTERNAL_JVM_ABI_GEN_CLASSPATH,
      KotlinBuilderFlags.INTERNAL_KAPT_CLASSPATH,
      KotlinBuilderFlags.INTERNAL_JDEPS_GEN_CLASSPATH,
    )

  /**
   * The task's Build Tools API runtime configuration, recorded on the toolchain info. The
   * --btapi_* and --internal_* flags are emitted together and only when the Build Tools API
   * compilation is enabled for the action, so the runtime flag set is none-or-everything:
   * all flags absent means a legacy request and the btapi message stays absent -- its
   * presence is the worker's signal to compile through the Build Tools API. A partial flag
   * set can only come from a malformed direct worker invocation and is rejected.
   *
   * The classpath fingerprint identifies the jars of the implementation classpath, the data the
   * cached compiler depends on; it is built from the request input digests ([fingerprintOf]) and
   * stays empty when the request carried none. It names the jars by the paths in [requestArgs],
   * which, unlike the resolved paths in [argMap], are the same in every sandbox.
   */
  private fun buildBtapiRuntime(
    argMap: ArgMap,
    requestArgs: ArgMap,
    toolchainInfo: KotlinToolchainInfo.Builder,
    inputDigests: Map<String, String>,
  ) {
    val missing = btapiRuntimeFlags.filter { argMap.optional(it) == null }
    if (missing.size == btapiRuntimeFlags.size) {
      return
    }
    check(missing.isEmpty()) {
      "incomplete Build Tools API runtime flag set: missing ${missing.joinToString { it.flag }}"
    }
    toolchainInfo.btapiBuilder.apply {
      addAllApiImplClasspath(argMap.mandatory(KotlinBuilderFlags.BTAPI_IMPL_CLASSPATH))
      addAllJvmAbiGenClasspath(
        argMap.mandatory(KotlinBuilderFlags.INTERNAL_JVM_ABI_GEN_CLASSPATH),
      )
      addAllKaptClasspath(argMap.mandatory(KotlinBuilderFlags.INTERNAL_KAPT_CLASSPATH))
      addAllJdepsGenClasspath(argMap.mandatory(KotlinBuilderFlags.INTERNAL_JDEPS_GEN_CLASSPATH))
      classpathFingerprint =
        fingerprintOf(requestArgs.mandatory(KotlinBuilderFlags.BTAPI_IMPL_CLASSPATH), inputDigests)
    }
  }

  private fun buildJvmTask(
    info: CompilationTaskInfo,
    workingDir: Path,
    argMap: ArgMap,
    paths: RequestPaths,
  ): JvmCompilationTask =
    JvmCompilationTask.newBuilder().let { root ->
      root.info = info

      root.compileKotlin = argMap.mandatorySingle(KotlinBuilderFlags.BUILD_KOTLIN).toBoolean()
      root.instrumentCoverage =
        argMap
          .mandatorySingle(
            KotlinBuilderFlags.INSTRUMENT_COVERAGE,
          ).toBoolean()

      with(root.outputsBuilder) {
        argMap.optionalSingle(KotlinBuilderFlags.OUTPUT)?.let { jar = it }
        argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_SRCJAR)?.let { srcjar = it }

        argMap.optionalSingle(KotlinBuilderFlags.OUTPUT_JDEPS)?.apply { jdeps = this }
        argMap.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_SRC_JAR)?.apply {
          generatedJavaSrcJar = this
        }
        argMap.optionalSingle(KotlinBuilderFlags.GENERATED_JAVA_STUB_JAR)?.apply {
          generatedJavaStubJar = this
        }
        argMap.optionalSingle(KotlinBuilderFlags.ABI_JAR)?.let { abijar = it }
        argMap.optionalSingle(KotlinBuilderFlags.GENERATED_CLASS_JAR)?.let {
          generatedClassJar = it
        }
      }

      with(root.directoriesBuilder) {
        val moduleName = argMap.mandatorySingle(KotlinBuilderFlags.MODULE_NAME)
        classes =
          workingDir.resolveNewDirectories(getOutputDirPath(moduleName, "classes")).toString()
        javaClasses =
          workingDir
            .resolveNewDirectories(
              getOutputDirPath(moduleName, "java_classes"),
            ).toString()
        if (argMap.hasAll(KotlinBuilderFlags.ABI_JAR)) {
          abiClasses =
            workingDir
              .resolveNewDirectories(
                getOutputDirPath(moduleName, "abi_classes"),
              ).toString()
        }
        generatedClasses =
          workingDir
            .resolveNewDirectories(getOutputDirPath(moduleName, "generated_classes"))
            .toString()
        temp =
          workingDir
            .resolveNewDirectories(
              getOutputDirPath(moduleName, "temp"),
            ).toString()
        generatedSources =
          workingDir
            .resolveNewDirectories(getOutputDirPath(moduleName, "generated_sources"))
            .toString()
        generatedJavaSources =
          workingDir
            .resolveNewDirectories(getOutputDirPath(moduleName, "generated_java_sources"))
            .toString()
        generatedStubClasses =
          workingDir.resolveNewDirectories(getOutputDirPath(moduleName, "stubs")).toString()
        coverageMetadataClasses =
          workingDir
            .resolveNewDirectories(getOutputDirPath(moduleName, "coverage-metadata"))
            .toString()
      }

      with(root.inputsBuilder) {
        addAllClasspath(argMap.mandatory(KotlinBuilderFlags.CLASSPATH))
        addAllDepsArtifacts(
          argMap.optional(KotlinBuilderFlags.DEPS_ARTIFACTS) ?: emptyList(),
        )
        addAllDirectDependencies(argMap.mandatory(KotlinBuilderFlags.DIRECT_DEPENDENCIES))

        addAllProcessors(argMap.optional(KotlinBuilderFlags.PROCESSORS) ?: emptyList())
        addAllProcessorpaths(argMap.optional(KotlinBuilderFlags.PROCESSOR_PATH) ?: emptyList())

        val plugins =
          argMap
            .optional(KotlinBuilderFlags.PLUGINS_PAYLOAD)
            ?.singleOrNull()
            ?.let(PluginsPayloadParser::parse)
            ?.map { plugin ->
              plugin
                .toBuilder()
                .clearClasspath()
                .addAllClasspath(plugin.classpathList.map(paths::resolve))
                .build()
            }
            ?: emptyList()
        addAllPlugins(plugins)

        // Expand the structured plugins into the per-phase fields the compilation paths consume.
        // The expansion reproduces the exact strings the legacy per-phase worker flags used to
        // carry, so the -Xplugin/-P arguments handed to kotlinc are unchanged.
        for (plugin in plugins) {
          val optionStrings =
            plugin.optionsList.map { option ->
              if (option.value.isEmpty()) {
                "${plugin.id}:${option.key}"
              } else {
                "${plugin.id}:${option.key}=${option.value}"
              }
            }
          if (JvmCompilationTask.Inputs.PluginPhase.PLUGIN_PHASE_STUBS in plugin.phasesList) {
            addAllStubsPluginClasspath(plugin.classpathList)
            addAllStubsPluginOptions(optionStrings)
          }
          if (JvmCompilationTask.Inputs.PluginPhase.PLUGIN_PHASE_COMPILE in plugin.phasesList) {
            addAllCompilerPluginClasspath(plugin.classpathList)
            addAllCompilerPluginOptions(optionStrings)
          }
        }

        argMap
          .optional(KotlinBuilderFlags.SOURCES)
          ?.iterator()
          ?.partitionJvmSources(
            { addKotlinSources(it) },
            { addJavaSources(it) },
          )
        argMap
          .optional(KotlinBuilderFlags.SOURCE_JARS)
          ?.also {
            addAllSourceJars(it)
          }
      }

      with(root.infoBuilder) {
        toolchainInfoBuilder.jvmBuilder.jvmTarget =
          argMap.mandatorySingle(KotlinBuilderFlags.JVM_TARGET)
      }
      root.build()
    }

  private fun getOutputDirPath(
    moduleName: String,
    dirName: String,
  ) = "_kotlinc/${moduleName}_jvm/$dirName"
}
