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

package io.bazel.worker

import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locates the files of a work request.
 *
 * Bazel names a request's inputs and outputs relative to the execution root. Under multiplex
 * sandboxing they live under the request's sandbox_dir instead, a directory relative to the
 * worker's working directory that the named paths do not include. [resolve] maps a path the
 * request names to the path the worker reads or writes; [relativize] maps such paths back, for
 * what a worker records or reports rather than opens, e.g. jdeps files or compiler diagnostics.
 */
class RequestPaths private constructor(
  /** The absolute sandbox directory; null when the files are relative to the working directory. */
  private val sandbox: Path?,
) {
  /**
   * The forms of the sandbox directory that start a [resolve]d path in text: absolute, as a file
   * URI, and relative to the working directory, as compilers print source paths.
   */
  private val sandboxPrefixes: List<String> =
    if (sandbox == null) {
      emptyList()
    } else {
      val absolute = sandbox.toString() + File.separator
      val workingDirectory = Paths.get("").toAbsolutePath()
      listOfNotNull(
        sandbox.toUri().toString().removeSuffix("/") + "/",
        absolute,
        workingDirectory
          .takeIf { sandbox.startsWith(it) && sandbox != it }
          ?.relativize(sandbox)
          ?.let { it.toString() + File.separator },
      )
    }

  /** Whether the request's files live in a sandbox rather than the working directory. */
  val isSandboxed: Boolean get() = sandbox != null

  /**
   * The path the worker reads or writes for [path], a path the request names. It keeps the path
   * as the request spells it, so that [relativize] restores it exactly.
   */
  fun resolve(path: String): String =
    if (sandbox == null || Paths.get(path).isAbsolute) {
      path
    } else {
      sandbox.toString() + File.separator + path
    }

  /**
   * [text], a [resolve]d path or a message that contains some, with the paths named the way the
   * request names them. Paths outside of the sandbox are left as they are.
   */
  fun relativize(text: String): String =
    sandboxPrefixes.fold(text) { relativized, prefix -> relativized.replace(prefix, "") }

  companion object {
    /** The paths of a request whose files are relative to the worker's working directory. */
    @JvmField
    val WORKING_DIRECTORY = RequestPaths(null)

    /** The paths of a request whose files live in [sandboxDir]. */
    @JvmStatic
    fun sandboxed(sandboxDir: Path): RequestPaths =
      RequestPaths(sandboxDir.toAbsolutePath().normalize())
  }
}
