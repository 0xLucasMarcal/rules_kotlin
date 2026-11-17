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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Paths

class RequestPathsTest {
  // Bazel names the sandbox_dir of a request relative to the worker's working directory.
  private val sandboxDir = Paths.get("__sandbox", "3", "_main")
  private val sandbox = Paths.get("").toAbsolutePath().resolve(sandboxDir)
  private val paths = RequestPaths.sandboxed(sandboxDir)

  private val jar = Paths.get("bazel-out", "bin", "lib.jar").toString()
  private val source = Paths.get("pkg", "A.kt").toString()

  @Test
  fun `request paths resolve into the sandbox`() {
    assertThat(paths.isSandboxed).isTrue()
    assertThat(Paths.get(paths.resolve(jar))).isEqualTo(sandbox.resolve(jar))
    assertThat(Paths.get(paths.resolve("bazel-out/bin/lib.jar"))).isEqualTo(sandbox.resolve(jar))
  }

  @Test
  fun `absolute paths are not resolved`() {
    val jdk = Paths.get("").toAbsolutePath().root.resolve("jdk").toString()
    assertThat(paths.resolve(jdk)).isEqualTo(jdk)
  }

  @Test
  fun `resolved paths relativize to the request paths`() {
    assertThat(paths.relativize(paths.resolve(jar))).isEqualTo(jar)
    assertThat(paths.relativize(paths.resolve("bazel-out/bin/lib.jar")))
      .isEqualTo("bazel-out/bin/lib.jar")
    assertThat(paths.relativize(sandbox.resolve(jar).toString())).isEqualTo(jar)
  }

  @Test
  fun `paths outside of the sandbox are not relativized`() {
    val elsewhere = Paths.get("").toAbsolutePath().resolve(jar).toString()
    assertThat(paths.relativize(elsewhere)).isEqualTo(elsewhere)
    assertThat(paths.relativize(jar)).isEqualTo(jar)
    // Another request's sandbox.
    val other = Paths.get("__sandbox", "13", "_main").resolve(jar).toString()
    assertThat(paths.relativize(other)).isEqualTo(other)
  }

  @Test
  fun `diagnostics name the sources the way the request does`() {
    val expected = "$source:2:5: error: unresolved reference"
    // Compilers print a source absolute, as a file URI, or relative to the working directory.
    for (printed in listOf(
      sandbox.resolve(source).toString(),
      sandbox.resolve(source).toUri().toString(),
      sandboxDir.resolve(source).toString(),
    )) {
      assertThat(paths.relativize("$printed:2:5: error: unresolved reference")).isEqualTo(expected)
    }
    assertThat(paths.relativize("warning: ${paths.resolve(jar)} does not exist"))
      .isEqualTo("warning: $jar does not exist")
  }

  @Test
  fun `the working directory leaves the paths as they are`() {
    val paths = RequestPaths.WORKING_DIRECTORY
    assertThat(paths.isSandboxed).isFalse()
    assertThat(paths.resolve(jar)).isEqualTo(jar)
    val absolute = Paths.get("").toAbsolutePath().resolve(jar).toString()
    assertThat(paths.relativize(absolute)).isEqualTo(absolute)
  }
}
