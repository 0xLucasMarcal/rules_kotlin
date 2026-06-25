/*
 * Copyright 2025 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.bazel.kotlin.compiler

import org.jetbrains.kotlin.cli.common.ExitCode

/**
 * Stub for the experimental Build Tools API compiler.
 *
 * The real implementation targets the redesigned Kotlin 2.4.0 Build Tools API
 * (`KotlinToolchains` / `JvmPlatformToolchain` / build sessions), which does not exist in the
 * Kotlin 2.2.20 build-tools API this fork is pinned to. This toolchain never enables
 * `experimental_build_tools_api`, so `BazelK2JVMCompiler` is always used and this class is never
 * loaded at runtime; it exists only to satisfy the reflective class lookup in KotlinToolchain.
 */
@Suppress("unused")
class BuildToolsAPICompiler {
  fun exec(
    errStream: java.io.PrintStream,
    vararg args: String,
  ): ExitCode =
    throw UnsupportedOperationException(
      "The experimental Build Tools API compiler is not supported with Kotlin 2.2.20 in this rules_kotlin fork.",
    )
}
