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

import io.bazel.kotlin.builder.KotlinJvmTestBuilder;
import io.bazel.kotlin.compiler.CompilationUnit;
import io.bazel.kotlin.compiler.CompilerConfiguration;
import io.bazel.kotlin.compiler.IncrementalCompilationSpec;
import io.bazel.kotlin.compiler.KotlinBtapiCompiler;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/** The incremental compilation contract of the Build Tools API compiler. */
@RunWith(JUnit4.class)
public class KotlinBtapiCompilerIncrementalTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    private static final String STDLIB = KotlinJvmTestBuilder.KOTLIN_STDLIB.singleCompileJar();

    private static final CompilerConfiguration CONFIGURATION =
            new CompilerConfiguration() {
                @Override public String getModuleName() { return "ic"; }
                @Override public String getJvmTarget() { return "11"; }
                @Override public String getApiVersion() { return "2.0"; }
                @Override public String getLanguageVersion() { return "2.0"; }
                @Override public List<String> getPassthroughArguments() { return Collections.emptyList(); }
                @Override public boolean getVerbose() { return false; }
            };

    private final KotlinBtapiCompiler compiler = KotlinJvmTestBuilder.btapiCompiler();

    private static CompilationUnit unit(List<String> sources, Path destination) {
        return new CompilationUnit() {
            @Override public List<String> getSources() { return sources; }
            @Override public List<String> getClasspath() { return Collections.singletonList(STDLIB); }
            @Override public List<String> getFriendPaths() { return Collections.emptyList(); }
            @Override public String getDestination() { return destination.toString(); }
        };
    }

    private static IncrementalCompilationSpec incremental(
            Path caches, List<String> modified, List<String> snapshots, boolean changesKnown) {
        return new IncrementalCompilationSpec() {
            @Override public String getWorkingDirectory() { return caches.toString(); }
            @Override public boolean getSourceChangesKnown() { return changesKnown; }
            @Override public List<String> getModifiedSources() { return modified; }
            @Override public List<String> getRemovedSources() { return Collections.emptyList(); }
            @Override public List<String> getClasspathSnapshots() { return snapshots; }
            @Override public List<String> getAdditionalOutputDirectories() { return Collections.emptyList(); }
        };
    }

    private void compile(CompilationUnit unit, IncrementalCompilationSpec incremental) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode =
                compiler.exec(
                        new PrintStream(out, true),
                        unit,
                        CONFIGURATION,
                        Collections.emptyList(),
                        incremental);
        assertWithMessage(new String(out.toByteArray(), StandardCharsets.UTF_8))
                .that(exitCode)
                .isEqualTo(0);
    }

    private Path write(String name, String... lines) throws IOException {
        Path file = temp.getRoot().toPath().resolve(name);
        Files.write(file, Arrays.asList(lines), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    public void snapshotsAClasspathEntry() throws IOException {
        Path snapshot = temp.getRoot().toPath().resolve("stdlib.bin");

        compiler.snapshotClasspathEntry(STDLIB, snapshot.toString(), true);

        assertThat(Files.size(snapshot)).isGreaterThan(0L);
    }

    @Test
    public void recompilesOnlyTheModifiedSources() throws IOException {
        Path a = write("A.kt", "package ic", "fun a(): Int = 1");
        Path b = write("B.kt", "package ic", "fun b(): Int = 2");
        Path classes = temp.newFolder("classes").toPath();
        Path caches = temp.newFolder("caches").toPath();
        Path snapshot = temp.getRoot().toPath().resolve("stdlib.bin");
        compiler.snapshotClasspathEntry(STDLIB, snapshot.toString(), true);
        List<String> sources = Arrays.asList(a.toString(), b.toString());
        List<String> snapshots = Collections.singletonList(snapshot.toString());

        compile(unit(sources, classes), incremental(caches, Collections.emptyList(), snapshots, false));
        Path aClass = classes.resolve("ic/AKt.class");
        Path bClass = classes.resolve("ic/BKt.class");
        byte[] aBefore = Files.readAllBytes(aClass);
        FileTime bBefore = Files.getLastModifiedTime(bClass);
        try (Stream<Path> cacheFiles = Files.list(caches)) {
            assertThat(cacheFiles.count()).isGreaterThan(0L);
        }

        write("A.kt", "package ic", "fun a(): Int = 3");
        compile(
                unit(sources, classes),
                incremental(caches, Collections.singletonList(a.toString()), snapshots, true));

        assertThat(Files.readAllBytes(aClass)).isNotEqualTo(aBefore);
        assertThat(Files.getLastModifiedTime(bClass)).isEqualTo(bBefore);
    }
}
