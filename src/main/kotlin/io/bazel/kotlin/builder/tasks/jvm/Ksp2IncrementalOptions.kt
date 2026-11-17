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

/**
 * How the KSP2 worker processes incrementally, set with worker startup flags. Startup flags are not
 * part of the actions' cache keys, so none of these settings invalidate a cached KSP2 action.
 */
data class Ksp2IncrementalOptions(
  /** Print one summary line per request in the action's output. */
  val summary: Boolean = false,
  /** Have KSP write its incremental logs to `logs` in the target's caches directory. */
  val kspLog: Boolean = false,
  /**
   * After each incremental processing, also process the sources from scratch and report the outputs
   * that differ, which doubles the processing time: for validating incremental processing only.
   */
  val verify: Boolean = false,
) {
  companion object {
    /** The worker startup flag that turns incremental processing on. */
    const val FLAG = "--experimental_incremental_ksp2"

    /**
     * The options set by the worker startup [flags], or null when they do not turn incremental
     * processing on.
     *
     * @throws IllegalArgumentException on an unknown or malformed incremental processing flag.
     */
    @JvmStatic
    fun fromWorkerFlags(flags: Iterable<String>): Ksp2IncrementalOptions? {
      var enabled = false
      var options = Ksp2IncrementalOptions()
      for (flag in flags.filter { it == FLAG || it.startsWith(FLAG + "_") }) {
        val name = flag.substringBefore('=')
        val value =
          when (if ('=' in flag) flag.substringAfter('=') else null) {
            null, "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("$name expects true or false, got: $flag")
          }
        options =
          when (name.removePrefix(FLAG)) {
            "" -> {
              enabled = value
              options
            }

            "_summary" -> {
              options.copy(summary = value)
            }

            "_log" -> {
              options.copy(kspLog = value)
            }

            "_verify" -> {
              options.copy(verify = value)
            }

            else -> {
              throw IllegalArgumentException("unknown incremental KSP2 flag: $flag")
            }
          }
      }
      return options.takeIf { enabled }
    }
  }
}
