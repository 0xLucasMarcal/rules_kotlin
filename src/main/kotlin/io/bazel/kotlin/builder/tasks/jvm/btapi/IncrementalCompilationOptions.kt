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

import java.time.Duration

/**
 * How the worker compiles incrementally, set with worker startup flags. Startup flags are not part
 * of the actions' cache keys, so none of these settings invalidate a cached compilation.
 */
data class IncrementalCompilationOptions(
  /**
   * Whether a target's state is created on its second compilation instead of its first. The first
   * compilation of a target is then a plain compilation that only records a marker: builds that
   * compile a target once, such as cold builds and CI, pay nothing for incremental compilation, and
   * the first incremental compilation of a target comes one compilation later.
   */
  val lazyState: Boolean = true,
  /**
   * The fewest sources outside source jars and generated directories a target compiles
   * incrementally with. Targets of generated code only, e.g. from protos, cannot benefit.
   */
  val minSources: Int = 1,
  /**
   * Whether targets built for the exec configuration, i.e. tools, compile incrementally. Their
   * state would duplicate the state of the same targets built for the target configuration.
   */
  val execConfiguration: Boolean = false,
  /** Print one summary line per compilation in the action's output. */
  val summary: Boolean = false,
  /** Append one JSON record per compilation to `log.jsonl` in the state root. */
  val log: Boolean = false,
  /** How long the state of a target that no compilation used is kept. */
  val retention: Duration = Duration.ofDays(7),
) {
  /** For Java callers: the default options but [lazyState]. */
  constructor(lazyState: Boolean) : this(lazyState, minSources = 1)

  /** For Java callers: the default options but [lazyState] and [minSources]. */
  constructor(
    lazyState: Boolean,
    minSources: Int,
  ) : this(lazyState, minSources, execConfiguration = false)

  companion object {
    /** The worker startup flag that turns incremental compilation on. */
    const val FLAG = "--experimental_incremental_compilation"

    /**
     * The options set by the worker startup [flags], or null when they do not turn incremental
     * compilation on.
     *
     * @throws IllegalArgumentException on an unknown or malformed incremental compilation flag.
     */
    @JvmStatic
    fun fromWorkerFlags(flags: Iterable<String>): IncrementalCompilationOptions? {
      var enabled = false
      var options = IncrementalCompilationOptions()
      for (flag in flags.filter { it == FLAG || it.startsWith(FLAG + "_") }) {
        val name = flag.substringBefore('=')
        val value = if ('=' in flag) flag.substringAfter('=') else null

        fun boolean(): Boolean =
          when (value) {
            null, "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("$name expects true or false, got: $flag")
          }

        fun number(): Long =
          value?.toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException("$name expects a non-negative number, got: $flag")
        options =
          when (name.removePrefix(FLAG)) {
            "" -> {
              enabled = boolean()
              options
            }

            "_state" -> {
              options.copy(
                lazyState =
                  when (value) {
                    "lazy" -> true
                    "eager" -> false
                    else -> throw IllegalArgumentException("$name expects lazy or eager: $flag")
                  },
              )
            }

            "_min_sources" -> {
              options.copy(minSources = number().toInt())
            }

            "_exec_configuration" -> {
              options.copy(execConfiguration = boolean())
            }

            "_summary" -> {
              options.copy(summary = boolean())
            }

            "_log" -> {
              options.copy(log = boolean())
            }

            "_retention_days" -> {
              options.copy(retention = Duration.ofDays(number()))
            }

            else -> {
              throw IllegalArgumentException("unknown incremental compilation flag: $flag")
            }
          }
      }
      return options.takeIf { enabled }
    }
  }
}
