/*
 * Copyright 2026 The Bazel Authors. All rights reserved.
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
package io.bazel.kotlin.builder.tasks.jvm

import com.google.common.truth.Truth.assertWithMessage
import com.google.devtools.build.lib.view.proto.Deps.Dependencies
import io.bazel.kotlin.builder.Deps.Dep
import io.bazel.kotlin.builder.KotlinJvmTestBuilder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Base64
import java.util.jar.JarFile
import kotlin.random.Random

/**
 * Applies random sequences of edits to a target and compares the outputs of each incremental
 * compilation with those of a full compilation of the same sources and dependencies: the class
 * jar, the ABI jar and the jdeps, byte for byte but for the manifest and the target label.
 *
 * The edits cover implementation and API changes, inline function bodies and constants (inlined
 * into their callers), added and removed sources, declarations moving to another Kotlin package,
 * API changes of a dependency, and a dependency moving to another Bazel package.
 *
 * Set EQUIVALENCE_SEEDS and EQUIVALENCE_STEPS in the test environment for longer runs.
 */
@RunWith(JUnit4::class)
class KotlinBuilderJvmBtaIncrementalEquivalenceTest {
  private val ctx = KotlinJvmTestBuilder()

  private enum class Type(
    val kotlin: String,
    val literal: (Int) -> String,
  ) {
    INT("Int", { "$it" }),
    LONG("Long", { "${it}L" }),
    STRING("String", { "\"$it\"" }),
  }

  private class Source(
    val index: Int,
    var pkg: String,
    var constant: Int,
    var inlineBody: Int,
    var type: Type,
    var value: Int,
    val extras: MutableSet<Int> = sortedSetOf(),
    val uses: MutableSet<Int> = sortedSetOf(),
    var usesDependency: Boolean = false,
  ) {
    val fileName = "F$index.kt"
  }

  private class Model(
    val random: Random,
  ) {
    val sources = sortedMapOf<Int, Source>()
    var nextIndex = 0
    var dependencyVersion = 0
    var dependencyMoves = 0

    fun text(source: Source): Array<String> =
      with(source) {
        val uses =
          uses.joinToString("") { used ->
            val pkg = sources.getValue(used).pkg
            " + $pkg.C$used().m().hashCode() + $pkg.K$used + $pkg.inl$used()"
          } + if (usesDependency) " + dep.D().d().hashCode()" else ""
        arrayOf(
          "package $pkg",
          "const val K$index = $constant",
          "inline fun inl$index(): Int = $inlineBody",
          "class C$index {",
          "  fun m(): ${type.kotlin} = ${type.literal(value)}",
          *extras.map { "  fun e$it(): Int = $it" }.toTypedArray(),
          "  fun use(): Int = 0$uses",
          "}",
        )
      }

    fun newSource(): Source =
      Source(
        index = nextIndex++,
        pkg = PACKAGES.random(random),
        constant = random.nextInt(100),
        inlineBody = random.nextInt(100),
        type = Type.values().random(random),
        value = random.nextInt(100),
      ).also { source ->
        sources.keys.shuffled(random).take(random.nextInt(3)).forEach { source.uses.add(it) }
      }
  }

  /** The dependency's versions, which differ in their API, and its copies at other paths. */
  private lateinit var dependencyVersions: List<Dep>

  private fun compileDependencies() {
    dependencyVersions =
      Type.values().mapIndexed { version, type ->
        ctx.runCompileTask({ c ->
          c.useBuildToolsApi()
          c.compileKotlin()
          c.addSource(
            "D.kt",
            "package dep",
            "class D {",
            "  fun d(): ${type.kotlin} = ${type.literal(version)}",
            "  fun v$version(): Int = $version",
            "}",
          )
          c.outputJar()
          c.outputAbiJar()
        })
      }
  }

  /** A copy of the dependency in another Bazel package: another path, the same contents. */
  private fun moved(
    dependency: Dep,
    moves: Int,
  ): Dep {
    val jar = Paths.get(dependency.singleCompileJar())
    val moved = Files.createTempDirectory("moved$moves").resolve(jar.fileName)
    Files.copy(jar, moved)
    return Dep
      .builder()
      .label(dependency.label())
      .moduleName(dependency.moduleName())
      .compileJars(listOf(moved.toString()))
      .build()
  }

  private class Outputs(
    val classes: Map<String, String>,
    val abi: Map<String, String>,
    val jdeps: Dependencies,
  )

  private fun outputs(dep: Dep): Outputs =
    Outputs(
      jarContents(ctx.outputJar()),
      jarContents(ctx.outputAbiJar()),
      Files.newInputStream(Paths.get(dep.jdeps())).use(Dependencies::parseFrom),
    )

  /** The entries of the jar, but its manifest, which names the target, by name. */
  private fun jarContents(jar: String): Map<String, String> =
    JarFile(jar).use { file ->
      file
        .entries()
        .asSequence()
        .filter { it.name != "META-INF/MANIFEST.MF" }
        .associate { it.name to Base64.getEncoder().encodeToString(file.getInputStream(it).readBytes()) }
        .toSortedMap()
    }

  private fun run(
    seed: Int,
    steps: Int,
  ) {
    KotlinJvmTestBuilder.restartWorker()
    compileDependencies()
    val model = Model(Random(seed))
    repeat(4) { model.newSource().also { model.sources[it.index] = it } }
    model.sources.values.forEach { source ->
      if (model.random.nextBoolean()) source.usesDependency = true
    }
    var dependency = dependencyVersions[0]
    val written = mutableMapOf<String, List<String>>()
    val log = mutableListOf("seed $seed")

    var dep =
      ctx.runCompileTask({ c ->
        c.useBuildToolsApi()
        c.incrementalCompilation()
        c.compileKotlin()
        c.addDirectDependencies(dependency)
        model.sources.values.forEach { source ->
          model.text(source).also {
            c.addSource(source.fileName, *it)
            written[source.fileName] = it.toList()
          }
        }
        c.outputJar()
        c.outputAbiJar()
        c.outputJdeps()
      })
    compare(model, dependency, dep, log)

    repeat(steps) { step ->
      val previousDependency = dependency
      val removed = mutableListOf<String>()
      val added = mutableListOf<String>()
      val edit = edit(model, removed, added)
      if (edit == "dependency api") {
        model.dependencyVersion = (model.dependencyVersion + 1 + model.random.nextInt(2)) % dependencyVersions.size
        dependency = dependencyVersions[model.dependencyVersion]
      } else if (edit == "dependency moved") {
        dependency = moved(dependency, ++model.dependencyMoves)
      }
      log.add("step $step: $edit")

      dep =
        ctx.rerunCompileTask({ c ->
          if (dependency !== previousDependency) {
            c.removeDependencies(previousDependency)
            c.addDirectDependencies(dependency)
          }
          removed.forEach {
            c.removeSource(it)
            written.remove(it)
          }
          model.sources.values.forEach { source ->
            val text = model.text(source)
            when {
              source.fileName in added -> c.addSource(source.fileName, *text)
              written[source.fileName] != text.toList() -> c.updateSource(source.fileName, *text)
            }
            written[source.fileName] = text.toList()
          }
        })
      compare(model, dependency, dep, log)
    }
  }

  /** Applies a random edit to the model; returns its description. */
  private fun edit(
    model: Model,
    removed: MutableList<String>,
    added: MutableList<String>,
  ): String {
    val random = model.random
    val source = model.sources.values.random(random)
    return when (random.nextInt(14)) {
      0, 1 -> {
        source.value = random.nextInt(1000)
        "body of ${source.fileName}"
      }

      2 -> {
        source.inlineBody = random.nextInt(1000)
        "inline function body of ${source.fileName}"
      }

      3 -> {
        source.constant = random.nextInt(1000)
        "constant of ${source.fileName}"
      }

      4 -> {
        source.type = Type.values().filter { it != source.type }.random(random)
        "return type of ${source.fileName}: ${source.type}"
      }

      5 -> {
        if (source.extras.isNotEmpty() && random.nextBoolean()) {
          source.extras.remove(source.extras.random(random))
          "member removed from ${source.fileName}"
        } else {
          source.extras.add(random.nextInt(10))
          "member added to ${source.fileName}"
        }
      }

      6 -> {
        val other = model.sources.keys.filter { it != source.index }.randomOrNull(random)
        if (other == null || !source.uses.add(other)) {
          source.uses.remove(other)
          "${source.fileName} stops using F$other"
        } else {
          "${source.fileName} uses F$other"
        }
      }

      7 -> {
        val new = model.newSource()
        model.sources[new.index] = new
        added.add(new.fileName)
        "added ${new.fileName}, using ${new.uses}"
      }

      8 -> {
        if (model.sources.size <= 2) {
          return "nothing"
        }
        model.sources.remove(source.index)
        model.sources.values.forEach { it.uses.remove(source.index) }
        removed.add(source.fileName)
        "removed ${source.fileName}"
      }

      9 -> {
        source.pkg = PACKAGES.filter { it != source.pkg }.random(random)
        "${source.fileName} moved to ${source.pkg}"
      }

      10 -> {
        "dependency api"
      }

      11 -> {
        "dependency moved"
      }

      12 -> {
        source.usesDependency = !source.usesDependency
        "${source.fileName} ${if (source.usesDependency) "uses" else "stops using"} the dependency"
      }

      else -> {
        "nothing"
      }
    }
  }

  /** Compiles the model's sources in full, in another task, and compares the outputs. */
  private fun compare(
    model: Model,
    dependency: Dep,
    dep: Dep,
    log: List<String>,
  ) {
    val incremental = outputs(dep)
    val saved = ctx.save()
    val full =
      try {
        outputs(
          ctx.runCompileTask({ c ->
            c.useBuildToolsApi()
            c.compileKotlin()
            c.addDirectDependencies(dependency)
            model.sources.values.forEach { c.addSource(it.fileName, *model.text(it)) }
            c.outputJar()
            c.outputAbiJar()
            c.outputJdeps()
          }),
        )
      } finally {
        ctx.restore(saved)
      }
    val context = log.joinToString("\n")
    assertWithMessage("class jar after\n$context")
      .that(difference(incremental.classes, full.classes))
      .isEmpty()
    assertWithMessage("ABI jar after\n$context").that(difference(incremental.abi, full.abi)).isEmpty()
    assertWithMessage("jdeps after\n$context")
      .that(incremental.jdeps.dependencyList)
      .containsExactlyElementsIn(full.jdeps.dependencyList)
      .inOrder()
  }

  /** The entries that differ between the incremental and the full compilation's jars. */
  private fun difference(
    incremental: Map<String, String>,
    full: Map<String, String>,
  ): List<String> =
    (incremental.keys + full.keys).sorted().mapNotNull { name ->
      when {
        name !in full -> "only incremental: $name"
        name !in incremental -> "only full: $name"
        incremental[name] != full[name] -> "differs: $name"
        else -> null
      }
    }

  @Test
  fun incrementalCompilationsMatchFullCompilations() {
    val seeds =
      System.getenv("EQUIVALENCE_SEEDS")?.split(',')?.map(String::trim)?.map(String::toInt)
        ?: listOf(1, 2, 3)
    val steps = System.getenv("EQUIVALENCE_STEPS")?.toInt() ?: 20
    try {
      seeds.forEach { run(it, steps) }
    } finally {
      KotlinJvmTestBuilder.restartWorker()
    }
  }

  private companion object {
    val PACKAGES = listOf("eq.p0", "eq.p1", "eq.p2")
  }
}
