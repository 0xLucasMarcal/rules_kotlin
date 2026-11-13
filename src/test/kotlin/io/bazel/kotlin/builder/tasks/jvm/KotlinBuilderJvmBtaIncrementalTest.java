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
package io.bazel.kotlin.builder.tasks.jvm;

import com.google.devtools.build.lib.view.proto.Deps.Dependencies;
import com.google.devtools.build.lib.view.proto.Deps.Dependency;
import io.bazel.kotlin.builder.Deps.Dep;
import io.bazel.kotlin.builder.KotlinJvmTestBuilder;
import io.bazel.kotlin.builder.tasks.jvm.btapi.IncrementalCompilation;
import io.bazel.kotlin.builder.tasks.jvm.btapi.IncrementalCompilationOptions;
import io.bazel.kotlin.builder.toolchain.CompilationStatusException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

/** Incremental compilation through the Build Tools API, across runs of the same target. */
@RunWith(JUnit4.class)
public class KotlinBuilderJvmBtaIncrementalTest {
    private static final KotlinJvmTestBuilder ctx = new KotlinJvmTestBuilder();

    private static final String[] A = {"package ic", "fun a(): Int = 1"};
    private static final String[] B = {"package ic", "fun b(): Int = a() + 1"};
    private static final String[] C = {"package ic", "class C { fun c() = \"c\" }"};

    private static void compileABC(boolean abiJar) {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.addSource("B.kt", B);
                    c.addSource("C.kt", C);
                    c.outputJar();
                    c.outputJdeps();
                    if (abiJar) {
                        c.outputAbiJar();
                    }
                });
    }

    /** The file names of the sources the compiler compiled, over every compile iteration. */
    private static List<String> compiledSources() {
        List<String> compiled = new ArrayList<>();
        boolean inIteration = false;
        for (String line : ctx.outLines()) {
            if (line.equals("Compile iteration:")) {
                inIteration = true;
            } else if (inIteration && line.startsWith("  ")) {
                String path = line.trim().split(" <- ")[0];
                compiled.add(Paths.get(path).getFileName().toString());
            } else {
                inIteration = false;
            }
        }
        return compiled;
    }

    private static boolean compiledFromScratch() {
        return ctx.outLines().stream().anyMatch(line -> line.endsWith("compiling from scratch"));
    }

    private static Dep compileDependency() {
        return compileDependency("dep", "Dependency");
    }

    private static Dep compileDependency(String packageName, String className) {
        return ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.compileKotlin();
                    c.addSource(className + ".kt", "package " + packageName, "class " + className);
                    c.outputJar();
                });
    }

    /** Compiles the sources with the dependencies, incrementally or not, and returns the jdeps. */
    private static Dependencies compileWithDependencies(
            boolean incremental, Map<String, String[]> sources, Dep... dependencies) {
        Dep dep = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    if (incremental) {
                        c.incrementalCompilation();
                    }
                    c.compileKotlin();
                    c.addDirectDependencies(dependencies);
                    sources.forEach(c::addSource);
                    c.outputJar();
                    c.outputJdeps();
                });
        return jdeps(dep);
    }

    private static List<String> jarEntries(String jar) {
        try (JarFile file = new JarFile(jar)) {
            return file.stream().map(e -> e.getName()).collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertJarContains(String jar, String... entries) {
        assertThat(jarEntries(jar)).containsAtLeastElementsIn(Arrays.asList(entries));
    }

    private static void assertJarLacks(String jar, String... entries) {
        assertThat(jarEntries(jar)).containsNoneIn(Arrays.asList(entries));
    }

    private static Dependencies jdeps(Dep dep) {
        try (InputStream in = Files.newInputStream(Paths.get(dep.jdeps()))) {
            return Dependencies.parseFrom(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void testFirstCompilationIsComplete() {
        compileABC(false);

        assertThat(ctx.outLines()).contains("|  source changes unknown");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testUnchangedSourcesCompileNothing() {
        compileABC(false);

        Dep dep = ctx.rerunCompileTask();

        assertThat(compiledSources()).isEmpty();
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
        assertThat(jarEntries(dep.singleCompileJar()))
                .containsAtLeast("ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testBodyChangeCompilesTheChangedSourceOnly() {
        compileABC(false);

        ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 2"));

        assertThat(compiledSources()).containsExactly("A.kt");
    }

    @Test
    public void testSignatureChangeCompilesTheDependentSources() {
        compileABC(true);

        // B.kt is unchanged, but its call to a() no longer links.
        ctx.rerunCompileTask(
                c -> c.updateSource("A.kt", "package ic", "fun a(offset: Int = 0): Int = 1 + offset"));
        assertThat(compiledSources()).containsAtLeast("A.kt", "B.kt");
        assertThat(compiledSources()).doesNotContain("C.kt");
        // The ABI of every compile iteration is kept.
        assertJarContains(ctx.outputAbiJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");

        ctx.rerunCompileTask(
                c -> c.updateSource(
                        "A.kt", "package ic", "fun a(offset: Int = 0): Int = 1 + offset", "fun x() = 0"));
        assertThat(compiledSources()).doesNotContain("C.kt");
    }

    @Test
    public void testRemovedSourceRemovesItsClasses() {
        compileABC(true);

        Dep dep = ctx.rerunCompileTask(c -> c.removeSource("C.kt"));

        assertJarLacks(ctx.outputJar(), "ic/C.class");
        assertJarLacks(ctx.outputAbiJar(), "ic/C.class");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class");
        assertThat(jarEntries(dep.singleCompileJar())).doesNotContain("ic/C.class");
    }

    @Test
    public void testAbiJarKeepsTheClassesOfUnchangedSources() {
        compileABC(true);
        List<String> before = jarEntries(ctx.rerunCompileTask().singleCompileJar());

        Dep dep = ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 3"));

        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(jarEntries(dep.singleCompileJar())).containsExactlyElementsIn(before);
        assertThat(before)
                .containsAtLeast(
                        "ic/AKt.class",
                        "ic/BKt.class",
                        "ic/C.class",
                        "META-INF/some_bogus_module.kotlin_module");
    }

    @Test
    public void testJdepsKeepTheDependenciesOfUnchangedSources() {
        Dep dependency = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.compileKotlin();
                    c.addSource("Dependency.kt", "package dep", "class Dependency");
                    c.outputJar();
                });
        Dep first = ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addDirectDependencies(dependency);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });
        assertThat(explicitDependencies(first)).contains(dependency.singleCompileJar());

        Dep second = ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 4"));

        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(explicitDependencies(second)).contains(dependency.singleCompileJar());
    }

    private static List<String> explicitDependencies(Dep dep) {
        return dependencies(dep, Dependency.Kind.EXPLICIT);
    }

    private static List<String> dependencies(Dep dep, Dependency.Kind kind) {
        return dependencies(jdeps(dep), kind);
    }

    private static List<String> dependencies(Dependencies jdeps, Dependency.Kind kind) {
        return jdeps.getDependencyList().stream()
                .filter(d -> d.getKind() == kind)
                .map(Dependency::getPath)
                .collect(Collectors.toList());
    }

    @Test
    public void testJdepsOfAnIncrementalCompilationMatchAFullCompilation() {
        Dep first = compileDependency("first", "First");
        Dep second = compileDependency("second", "Second");
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", new String[] {"package ic", "fun a() = first.First()"});
        sources.put("B.kt", new String[] {"package ic", "fun b() = second.Second()"});
        sources.put("C.kt", C);
        compileWithDependencies(true, sources, first, second);

        // A.kt stops using the first dependency; B.kt, which uses the second, is not recompiled.
        String[] a = {"package ic", "fun a(): Any = 1"};
        Dependencies incremental =
                jdeps(ctx.rerunCompileTask(c -> c.updateSource("A.kt", a)));
        assertThat(compiledSources()).containsExactly("A.kt");

        sources.put("A.kt", a);
        Dependencies full = compileWithDependencies(false, sources, first, second);
        assertThat(incremental.getDependencyList())
                .containsExactlyElementsIn(full.getDependencyList())
                .inOrder();
        assertThat(dependencies(incremental, Dependency.Kind.UNUSED))
                .containsExactly(first.singleCompileJar());
        assertThat(dependencies(incremental, Dependency.Kind.EXPLICIT))
                .contains(second.singleCompileJar());
    }

    @Test
    public void testRemovedSourceDropsItsDependencies() {
        Dep dependency = compileDependency();
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", A);
        sources.put("UsesDependency.kt", new String[] {"package ic", "fun use() = dep.Dependency()"});
        compileWithDependencies(true, sources, dependency);

        Dependencies jdeps = jdeps(ctx.rerunCompileTask(c -> c.removeSource("UsesDependency.kt")));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(dependencies(jdeps, Dependency.Kind.UNUSED))
                .containsExactly(dependency.singleCompileJar());
        assertThat(dependencies(jdeps, Dependency.Kind.EXPLICIT))
                .doesNotContain(dependency.singleCompileJar());
    }

    @Test
    public void testAddedDependencyKeepsTheState() {
        Dep dependency = compileDependency();
        compileABC(false);

        Dep dep = ctx.rerunCompileTask(c -> {
            c.addDirectDependencies(dependency);
            c.updateSource("A.kt", "package ic", "fun a(): Int = dep.Dependency().hashCode()");
        });

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(explicitDependencies(dep)).contains(dependency.singleCompileJar());
    }

    @Test
    public void testDependencyChangesUpdateTheJdeps() {
        Dep used = compileDependency();
        Dep unused = compileDependency();
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addDirectDependencies(used, unused);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });

        // Nothing recompiles: the jdeps plugin does not run.
        Dep dep = ctx.rerunCompileTask(c -> {
            c.removeDependencies(unused);
            c.makeTransitive(used);
        });

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).isEmpty();
        assertThat(explicitDependencies(dep)).containsExactly(used.singleCompileJar());
        assertThat(dependencies(dep, Dependency.Kind.UNUSED)).isEmpty();
    }

    /** The dependency's jar under another path, with the same contents. */
    private static Dep movedDependency(Dep dependency) {
        try {
            Path jar = Paths.get(dependency.singleCompileJar());
            Path moved = Files.createTempDirectory("moved").resolve(jar.getFileName());
            Files.copy(jar, moved);
            return Dep.builder()
                    .label(dependency.label())
                    .moduleName(dependency.moduleName())
                    .compileJars(Collections.singletonList(moved.toString()))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void testSourcesUsingAMovedDependencyRecordItsNewPath() {
        Dep dependency = compileDependency();
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", A);
        sources.put("UsesDependency.kt", new String[] {"package ic", "fun use() = dep.Dependency()"});
        compileWithDependencies(true, sources, dependency);

        // The compiler sees no change: the moved jar has the same contents.
        Dep moved = movedDependency(dependency);
        Dep dep = ctx.rerunCompileTask(c -> {
            c.removeDependencies(dependency);
            c.addDirectDependencies(moved);
        });

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).containsExactly("UsesDependency.kt");
        assertThat(explicitDependencies(dep)).contains(moved.singleCompileJar());
        assertThat(explicitDependencies(dep)).doesNotContain(dependency.singleCompileJar());
    }

    @Test
    public void testDependencyThatIsNoLongerDirectFailsStrictDeps() {
        Dep dependency = compileDependency();
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.kotlinStrictDeps("error");
                    c.addDirectDependencies(dependency);
                    c.addSource("A.kt", A);
                    c.addSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency()");
                    c.outputJar();
                    c.outputJdeps();
                });

        ctx.runFailingCompileTaskAndValidateOutput(
                () -> ctx.rerunCompileTask(c -> {
                    c.makeTransitive(dependency);
                    c.updateSource("A.kt", "package ic", "fun a(): Int = 5");
                }),
                lines -> {
                    // The check covers the dependencies of UsesDependency.kt, which is not recompiled.
                    assertThat(compiledFromScratch()).isFalse();
                    assertThat(compiledSources()).containsExactly("A.kt");
                    assertThat(
                                    lines.stream()
                                            .anyMatch(l -> l.contains(
                                                    "Please add the following dependencies")))
                            .isTrue();
                });
    }

    @Test
    public void testWithoutInputDigestsCompilesNonIncrementally() {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.withoutInputDigests();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.outputJar();
                });

        assertThat(ctx.outLines()).contains("incremental compilation unavailable:");
        assertJarContains(ctx.outputJar(), "ic/AKt.class");
    }

    @Test
    public void testRootResetBetweenRequestsDisablesIncrementalCompilation() throws IOException {
        KotlinJvmTestBuilder.restartWorker();
        try {
            compileABC(false);

            // A sandboxed worker's directory is reset before each request.
            try (Stream<Path> paths = Files.walk(KotlinJvmTestBuilder.incrementalCompilationRoot())) {
                paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 6"));
            assertThat(ctx.outLines()).contains("warning: incremental compilation disabled:");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");

            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 7"));
            assertThat(ctx.outLines()).contains("incremental compilation unavailable:");
            assertThat(ctx.outLines()).doesNotContain("warning: incremental compilation disabled:");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    @Test
    public void testCompilationErrorKeepsThePreviousState() {
        compileABC(false);

        assertThrows(
                CompilationStatusException.class,
                () -> ctx.rerunCompileTask(c -> c.updateSource("B.kt", "package ic", "fun b(): Int = a() +")));

        ctx.rerunCompileTask(c -> c.updateSource("B.kt", "package ic", "fun b(): Int = a() + 2"));

        assertThat(ctx.outLines()).doesNotContain("|  source changes unknown");
        assertThat(compiledSources()).containsExactly("B.kt");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @Test
    public void testCompilationErrorInALaterRoundKeepsThePreviousState() {
        compileABC(true);

        // A.kt compiles; B.kt, recompiled in the next round because a() changed, no longer does.
        assertThrows(
                CompilationStatusException.class,
                () -> ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): String = \"1\"")));
        assertThat(compiledSources()).containsAtLeast("A.kt", "B.kt");

        ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 2"));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(ctx.outLines()).doesNotContain("|  source changes unknown");
        assertThat(compiledSources()).doesNotContain("C.kt");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
        assertJarContains(ctx.outputAbiJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    private static Dep compileDependencyWith(String... lines) {
        return ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.compileKotlin();
                    c.addSource("Dependency.kt", lines);
                    c.outputJar();
                    c.outputAbiJar();
                });
    }

    @Test
    public void testCompilationErrorFromADependencyChangeKeepsThePreviousState() {
        Dep before = compileDependencyWith("package dep", "class Dependency { fun f() = 1 }");
        Dep after = compileDependencyWith("package dep", "class Dependency { fun g() = 1 }");
        Map<String, String[]> sources = new LinkedHashMap<>();
        sources.put("A.kt", A);
        sources.put("UsesDependency.kt", new String[] {"package ic", "fun use() = dep.Dependency().f()"});
        sources.put("C.kt", C);
        compileWithDependencies(true, sources, before);

        // The dependency's API change breaks a source that did not change.
        assertThrows(
                CompilationStatusException.class,
                () -> ctx.rerunCompileTask(c -> {
                    c.removeDependencies(before);
                    c.addDirectDependencies(after);
                }));

        ctx.rerunCompileTask(
                c -> c.updateSource("UsesDependency.kt", "package ic", "fun use() = dep.Dependency().g()"));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).containsExactly("UsesDependency.kt");
    }

    @Test
    public void testConfigurationChangeCompilesFromScratch() {
        compileABC(false);

        ctx.rerunCompileTask(c -> c.addPassthroughFlags("-Xno-call-assertions"));

        assertThat(ctx.outLines())
                .contains("|  the compilation configuration changed, compiling from scratch");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    // --- Targets that cannot benefit compile non-incrementally ---

    @Test
    public void testSourceJarOnlyTargetCompilesNonIncrementally() {
        KotlinJvmTestBuilder.restartWorker();
        Map<String, String[]> generated = new LinkedHashMap<>();
        generated.put("gen/G.kt", new String[] {"package gen", "class G"});
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.sourceJar("generated.srcjar", generated);
                    c.outputJar();
                    c.outputJdeps();
                });

        assertThat(ctx.outLines()).contains(
                "|  the target has no sources outside source jars and generated directories");
        assertJarContains(ctx.outputJar(), "gen/G.class");
        assertThat(stateDirectories()).isEmpty();
    }

    @Test
    public void testOptedOutTargetCompilesNonIncrementally() {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.incrementalCompilationOptOut();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.outputJar();
                });

        assertThat(ctx.outLines())
                .contains("|  the target opts out with the kt_no_incremental_compilation tag");
        assertJarContains(ctx.outputJar(), "ic/AKt.class");
    }

    @Test
    public void testTargetWithFewerSourcesThanTheMinimumCompilesNonIncrementally() {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions(false, 4));
        try {
            compileABC(false);

            assertThat(ctx.outLines()).contains(
                    "|  the target has 3 sources outside source jars and generated directories, fewer than 4");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    @Test
    public void testExecConfigurationCompilesNonIncrementally() {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addSource("A.kt", A);
                    c.outputJarIn("bazel-out/k8-opt-exec-ST-0123456789ab/bin/lib");
                });

        assertThat(ctx.outLines()).contains("|  the target is built for the exec configuration");
        assertJarContains(ctx.outputJar(), "ic/AKt.class");
    }

    // --- Lazy state ---

    @Test
    public void testLazyStateIsCreatedByTheSecondCompilation() {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions());
        try {
            compileABC(true);
            assertThat(ctx.outLines()).contains(
                    "|  no previous compilation state, compiling without state: the next compilation creates it");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");

            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 8"));
            assertThat(ctx.outLines()).contains("|  source changes unknown");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
            assertJarContains(ctx.outputAbiJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");

            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 9"));
            assertThat(compiledSources()).containsExactly("A.kt");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    @Test
    public void testLazyStateCountsAFailedFirstCompilation() {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions());
        try {
            assertThrows(
                    CompilationStatusException.class,
                    () -> ctx.runCompileTask(
                            c -> {
                                c.useBuildToolsApi();
                                c.incrementalCompilation();
                                c.compileKotlin();
                                c.addSource("A.kt", A);
                                c.addSource("B.kt", "package ic", "fun b(): Int = a() +");
                                c.outputJar();
                            }));

            ctx.rerunCompileTask(c -> c.updateSource("B.kt", B));

            assertThat(ctx.outLines()).contains("|  source changes unknown");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    @Test
    public void testLazyStateStartsOverAfterAConfigurationChange() {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions());
        try {
            compileABC(false);

            ctx.rerunCompileTask(c -> c.addPassthroughFlags("-Xno-call-assertions"));

            assertThat(ctx.outLines()).contains(
                    "|  no previous compilation state, compiling without state: the next compilation creates it");
            assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    // --- Source jars of mixed targets ---

    @Test
    public void testSourcesFromSourceJarsAreStaged() {
        Map<String, String[]> generated = new LinkedHashMap<>();
        generated.put("gen/G.kt", new String[] {"package gen", "fun g(): Int = 1"});
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.addSource("A.kt", "package ic", "fun a() = gen.g()");
                    c.addSource("C.kt", C);
                    c.sourceJar("generated.srcjar", generated);
                    c.outputJar();
                    c.outputJdeps();
                });

        ctx.rerunCompileTask(c -> c.updateSource("C.kt", "package ic", "class C { fun c() = \"d\" }"));
        assertThat(compiledSources()).containsExactly("C.kt");

        generated.put("gen/G.kt", new String[] {"package gen", "fun g(): Long = 2"});
        ctx.rerunCompileTask(c -> c.sourceJar("generated.srcjar", generated));
        assertThat(compiledSources()).containsAtLeast("G.kt", "A.kt");
        assertThat(compiledSources()).doesNotContain("C.kt");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/C.class", "gen/GKt.class");
    }

    // --- Coverage instrumentation rewrites classes in place: never those of the state ---

    @Test
    public void testCoverageInstrumentsCopiesOfTheStateClasses() throws IOException {
        ctx.runCompileTask(
                c -> {
                    c.useBuildToolsApi();
                    c.incrementalCompilation();
                    c.compileKotlin();
                    c.coverage();
                    c.addSource("A.kt", A);
                    c.addSource("B.kt", B);
                    c.outputJar();
                });
        ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 12"));

        assertThat(compiledSources()).containsExactly("A.kt");
        assertThat(jarEntries(ctx.outputJar())).contains("ic/BKt.class.uninstrumented");
        // The state's classes, not those discarded to the trash, which the worker deletes meanwhile.
        List<Path> stateBKt = stateDirectories().stream()
                .map(dir -> dir.resolve("classes").resolve("ic").resolve("BKt.class"))
                .filter(Files::exists)
                .collect(Collectors.toList());
        assertThat(stateBKt).isNotEmpty();
        for (Path classFile : stateBKt) {
            String bytes = new String(Files.readAllBytes(classFile), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertThat(bytes).doesNotContain("$jacocoData");
        }
    }

    // --- The compiler skips the classpath comparison when it has not changed ---

    @Test
    public void testUnchangedClasspathIsAssured() {
        Dep dependency = compileDependency();
        compileABC(false);

        ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 10"));
        assertThat(ctx.outLines()).contains("|  classpath unchanged");

        ctx.rerunCompileTask(c -> c.addDirectDependencies(dependency));
        assertThat(ctx.outLines()).doesNotContain("|  classpath unchanged");
        assertThat(compiledFromScratch()).isFalse();
    }

    // --- Compilations that do not complete ---

    private static final String[] A_WITH_OFFSET = {"package ic", "fun a(offset: Int = 0): Int = 1 + offset"};
    private static final String[] C_CHANGED = {"package ic", "class C { fun c() = \"changed\" }"};

    /**
     * Interrupts the compilation of an API change at the checkpoint, as a killed worker would: the
     * state is what the compilation left on disk at that point.
     */
    private static void interruptAt(IncrementalCompilation.Checkpoint at) throws IOException {
        compileABC(true);
        Path saved = Files.createTempDirectory("interrupted");
        Path[] stateDir = new Path[1];
        KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {
            if (checkpoint == at) {
                stateDir[0] = dir;
                copyDirectory(dir, saved);
            }
        });
        try {
            ctx.rerunCompileTask(c -> c.updateSource("A.kt", A_WITH_OFFSET));
        } finally {
            KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {});
        }
        assertThat((Object) stateDir[0]).isNotNull();
        deleteDirectory(stateDir[0]);
        copyDirectory(saved, stateDir[0]);
    }

    /** The outputs of the next compilation after an interruption are those of a full compilation. */
    private static void assertRecoversFromInterruptionAt(IncrementalCompilation.Checkpoint at)
            throws IOException {
        interruptAt(at);

        Dep dep = ctx.rerunCompileTask(c -> c.updateSource("C.kt", C_CHANGED));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(ctx.outLines())
                .contains("|  the previous compilation did not complete, restored the state before it");
        // Changed since the last compilation that completed.
        assertThat(compiledSources()).containsAtLeast("A.kt", "C.kt");
        assertSameAsFullCompilation(dep, A_WITH_OFFSET, B, C_CHANGED);
    }

    @Test
    public void testInterruptionAfterTheBackupRestoresTheState() throws IOException {
        assertRecoversFromInterruptionAt(IncrementalCompilation.Checkpoint.BACKED_UP);
    }

    @Test
    public void testInterruptionAfterTheCompilerRestoresTheState() throws IOException {
        assertRecoversFromInterruptionAt(IncrementalCompilation.Checkpoint.COMPILED);
    }

    @Test
    public void testInterruptionBeforeTheCommitRestoresTheState() throws IOException {
        assertRecoversFromInterruptionAt(IncrementalCompilation.Checkpoint.RECORDED);
    }

    @Test
    public void testCancelledCompilationKeepsThePreviousState() {
        compileABC(true);
        KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {
            if (checkpoint == IncrementalCompilation.Checkpoint.COMPILED) {
                // As a cancelled request, whose thread Bazel interrupts.
                KotlinBuilderJvmBtaIncrementalTest.<RuntimeException>sneakyThrow(new InterruptedException());
            }
        });
        try {
            assertThrows(
                    InterruptedException.class,
                    () -> ctx.rerunCompileTask(c -> c.updateSource("A.kt", A_WITH_OFFSET)));
        } finally {
            KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {});
        }

        Dep dep = ctx.rerunCompileTask(c -> c.updateSource("C.kt", C_CHANGED));

        assertThat(compiledFromScratch()).isFalse();
        assertThat(compiledSources()).containsAtLeast("A.kt", "C.kt");
        assertSameAsFullCompilation(dep, A_WITH_OFFSET, B, C_CHANGED);
    }

    @Test
    public void testInternalFailureDiscardsTheState() {
        compileABC(false);
        KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {
            if (checkpoint == IncrementalCompilation.Checkpoint.COMPILED) {
                throw new IllegalStateException("internal failure");
            }
        });
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> ctx.rerunCompileTask(c -> c.updateSource("A.kt", A_WITH_OFFSET)));
        } finally {
            KotlinJvmTestBuilder.onCheckpoint((checkpoint, dir) -> {});
        }

        ctx.rerunCompileTask(c -> c.updateSource("C.kt", C_CHANGED));

        assertThat(ctx.outLines()).contains("|  source changes unknown");
        assertJarContains(ctx.outputJar(), "ic/AKt.class", "ic/BKt.class", "ic/C.class");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }

    /** The jar entries, but the manifest, which names the target. */
    private static Map<String, String> jarContents(String jar) {
        Map<String, String> contents = new java.util.TreeMap<>();
        try (JarFile file = new JarFile(jar)) {
            for (java.util.jar.JarEntry entry : Collections.list(file.entries())) {
                if (!entry.getName().equals("META-INF/MANIFEST.MF")) {
                    try (InputStream in = file.getInputStream(entry)) {
                        contents.put(entry.getName(), java.util.Base64.getEncoder().encodeToString(in.readAllBytes()));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return contents;
    }

    /** Compares the class jar and the ABI jar with those of a full compilation of the sources. */
    private static void assertSameAsFullCompilation(Dep dep, String[] a, String[] b, String[] c) {
        Map<String, String> classes = jarContents(ctx.outputJar());
        Map<String, String> abi = jarContents(ctx.outputAbiJar());
        KotlinJvmTestBuilder.SavedTask incremental = ctx.save();
        ctx.runCompileTask(
                task -> {
                    task.useBuildToolsApi();
                    task.compileKotlin();
                    task.addSource("A.kt", a);
                    task.addSource("B.kt", b);
                    task.addSource("C.kt", c);
                    task.outputJar();
                    task.outputJdeps();
                    task.outputAbiJar();
                });
        try {
            assertThat(classes).isEqualTo(jarContents(ctx.outputJar()));
            assertThat(abi).isEqualTo(jarContents(ctx.outputAbiJar()));
        } finally {
            ctx.restore(incremental);
        }
    }

    // --- Observability and disk usage ---

    @Test
    public void testSummaryAndLog() throws IOException {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions(
                false, 1, false, true, true, java.time.Duration.ofDays(7)));
        try {
            compileABC(false);
            assertThat(ctx.outLines().stream().anyMatch(
                    l -> l.startsWith("kotlin incremental compilation: full"))).isTrue();

            ctx.rerunCompileTask(c -> c.updateSource("A.kt", "package ic", "fun a(): Int = 11"));
            String summary = ctx.outLines().stream()
                    .filter(l -> l.startsWith("kotlin incremental compilation: incremental"))
                    .findFirst()
                    .orElseThrow(AssertionError::new);
            assertThat(summary).contains("1/3 sources compiled");
            assertThat(summary).contains("compile ");

            List<String> log = Files.readAllLines(KotlinJvmTestBuilder.incrementalCompilationRoot().resolve("log.jsonl"));
            assertThat(log).hasSize(2);
            assertThat(log.get(0)).contains("\"mode\":\"full\"");
            assertThat(log.get(1)).contains("\"mode\":\"incremental\"");
            assertThat(log.get(1)).contains("\"recompiled\":1");
            assertThat(log.get(1)).contains("\"classpath_unchanged\":true");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    @Test
    public void testUnusedStateIsPruned() throws Exception {
        KotlinJvmTestBuilder.restartWorker(new IncrementalCompilationOptions(false));
        try {
            compileABC(false);
            List<Path> states = stateDirectories();
            assertThat(states).hasSize(1);
            Files.setLastModifiedTime(
                    states.get(0).resolve(".lock"),
                    java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8))));

            // The next worker prunes the root on its first compilation.
            KotlinJvmTestBuilder.restartWorkerKeepingState(new IncrementalCompilationOptions(false));
            ctx.runCompileTask(
                    c -> {
                        c.useBuildToolsApi();
                        c.incrementalCompilation();
                        c.compileKotlin();
                        c.addSource("Other.kt", "package other", "class Other");
                        c.outputJar();
                    });

            // The maintenance runs in the background.
            Path usage = KotlinJvmTestBuilder.incrementalCompilationRoot().resolve("usage.json");
            String report = "";
            for (int i = 0; i < 100 && !report.contains("\"pruned_targets\": 1"); i++) {
                Thread.sleep(100);
                report = new String(Files.readAllBytes(usage), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertThat(Files.exists(states.get(0))).isFalse();
            assertThat(stateDirectories()).hasSize(1);
            assertThat(report).contains("\"pruned_targets\": 1");
            assertThat(report).contains("\"targets\": 1");
        } finally {
            KotlinJvmTestBuilder.restartWorker();
        }
    }

    /** The state directories of the targets under the current worker's root. */
    private static List<Path> stateDirectories() {
        Path root = KotlinJvmTestBuilder.incrementalCompilationRoot();
        if (!Files.isDirectory(root)) {
            return Collections.emptyList();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(p -> !java.util.Set.of("workers", "classpath-snapshots", "trash")
                            .contains(p.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void copyDirectory(Path from, Path to) {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path source : paths.collect(Collectors.toList())) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteDirectory(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
