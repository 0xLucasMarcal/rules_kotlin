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
@file:JvmName("IOUtils")

package io.bazel.kotlin.builder.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

fun Path.resolveNewDirectories(vararg parts: String) =
  Files.createDirectories(
    parts.fold(this, Path::resolve),
  )

fun Path.verified(): File =
  this
    .toFile()
    .also { check(it.exists()) { "file did not exist: $this" } }

/**
 * The file [path] resolves to, through the symbolic links a sandbox places its inputs as, for the
 * files a worker keeps open beyond the request; [path] itself if it cannot be resolved.
 */
fun realPathOf(path: String): String =
  try {
    Paths.get(path).toRealPath().toString()
  } catch (_: IOException) {
    path
  }
