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

import java.time.Instant
import java.util.concurrent.TimeUnit

/** What one compilation of a target did, for the summary line and the JSON log. */
internal class CompilationSummary(
  private val label: String,
) {
  enum class Mode(
    val text: String,
  ) {
    /** Compiled against the state of the previous compilation. */
    INCREMENTAL("incremental"),

    /** Compiled every source and created the state. */
    FULL("full"),

    /** Compiled without state; with lazy state, the next compilation creates it. */
    PLAIN("plain"),

    /** The target does not compile incrementally. */
    SKIPPED("skipped"),

    /** The worker cannot keep state. */
    UNAVAILABLE("unavailable"),
  }

  private val started = System.nanoTime()
  private val phases = LinkedHashMap<String, Long>()

  var mode = Mode.PLAIN
  var reason: String? = null
  var sources = 0

  /** The sources whose changes the compiler was told of, or null when it was told of none. */
  var modified: Int? = null
  var removed: Int? = null

  /** The sources the compiler compiled over all of its rounds, or null when unknown. */
  var recompiled: Int? = null
  var classpathUnchanged: Boolean? = null

  /** Why the compilation failed, or null if it did not. */
  var failure: String? = null

  /** Runs [block], adding its duration to the [phase]. */
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

  private fun millis(nanos: Long) = TimeUnit.NANOSECONDS.toMillis(nanos)

  /** One line for the action's output. */
  fun line(): String =
    buildString {
      append("kotlin incremental compilation: ").append(mode.text)
      reason?.let { append(" (").append(it).append(')') }
      append(", ")
      recompiled?.let { append(it).append('/') }
      append(sources).append(" sources")
      if (recompiled != null) {
        append(" compiled")
      }
      failure?.let { append(", failed: ").append(it) }
      phases.forEach { (phase, nanos) ->
        append(", ")
          .append(phase)
          .append(' ')
          .append(millis(nanos))
          .append(" ms")
      }
      append(", total ").append(millis(System.nanoTime() - started)).append(" ms")
    }

  /** One JSON object, on a single line. */
  fun json(): String =
    buildString {
      append('{')
      field("time", Instant.now().toString())
      field("label", label)
      field("mode", mode.text)
      reason?.let { field("reason", it) }
      field("sources", sources)
      modified?.let { field("modified", it) }
      removed?.let { field("removed", it) }
      recompiled?.let { field("recompiled", it) }
      classpathUnchanged?.let { field("classpath_unchanged", it) }
      failure?.let { field("failure", it) }
      append("\"phases_ms\":{")
      phases.entries.forEachIndexed { index, (phase, nanos) ->
        if (index > 0) append(',')
        append(quote(phase)).append(':').append(millis(nanos))
      }
      append("},")
      append("\"total_ms\":").append(millis(System.nanoTime() - started))
      append('}')
    }

  private fun StringBuilder.field(
    name: String,
    value: Any,
  ) {
    append(quote(name)).append(':')
    append(if (value is String) quote(value) else value.toString())
    append(',')
  }

  companion object {
    /** The phases of a compilation, in the order they run. */
    const val STAGING = "staging"
    const val SNAPSHOTS = "snapshots"
    const val BACKUP = "backup"
    const val COMPILE = "compile"
    const val COMMIT = "commit"
    const val OUTPUTS = "outputs"

    /** [value] as a JSON string. */
    fun quote(value: String): String =
      buildString {
        append('"')
        value.forEach { c ->
          when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
          }
        }
        append('"')
      }
  }
}
