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

/**
 * The typed contract between the KSP2 worker and the Ksp2Invoker implementation loaded in the
 * KSP2 classloader.
 *
 * The worker defines this interface and the invoker implements it; because the KSP2 classloader
 * parents to the worker's classloader, both sides see the same interface class and the worker
 * invokes the implementation directly instead of looking the method up reflectively. Only JDK
 * types cross the boundary, so the contract does not leak KSP types into the worker.
 */
interface Ksp2EntryPoint {
  /**
   * Execute KSP2 with the given configuration.
   *
   * @param incremental whether KSP processes against the caches in [cachesDir], updating the
   *   outputs in place, instead of processing every source.
   * @param modifiedSources the sources that changed since the caches were written.
   * @param removedSources the sources that were removed since the caches were written.
   * @param changedClasses the classpath classes that changed since the caches were written, by
   *   binary name with nested classes separated by dots.
   * @param logLevel Logger level (0=ERROR, 1=WARN, 2=INFO, 3=LOGGING)
   * @return Exit code (0 for success)
   */
  @Suppress("LongParameterList")
  fun execute(
    moduleName: String,
    sourceRoots: List<File>,
    javaSourceRoots: List<File>,
    libraries: List<File>,
    kotlinOutputDir: File,
    javaOutputDir: File,
    classOutputDir: File,
    resourceOutputDir: File,
    cachesDir: File,
    projectBaseDir: File,
    outputBaseDir: File,
    jvmTarget: String?,
    languageVersion: String?,
    apiVersion: String?,
    jdkHome: File?,
    processorOptions: Map<String, String> = emptyMap(),
    experimentalPsiResolution: Boolean = false,
    incremental: Boolean = false,
    incrementalLog: Boolean = false,
    modifiedSources: List<File> = emptyList(),
    removedSources: List<File> = emptyList(),
    changedClasses: List<String> = emptyList(),
    logLevel: Int = 1,
  ): Int
}
