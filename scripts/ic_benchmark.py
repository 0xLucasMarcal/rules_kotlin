#!/usr/bin/env python3
"""Benchmarks experimental Kotlin incremental compilation (IC) against plain compilation.

Methodology:
  * Every build targets one top-level target; each edit inserts a unique marker, so no cache can
    serve it.
  * Modes are alternated run by run (off, then each IC mode), and medians are reported.
  * Cold builds run after `bazel clean`, without a disk cache.
  * Incremental builds edit a target after IC state exists for every target. An
    "implementation" edit adds a local val inside an existing function body (the ABI is
    unchanged); an "API" edit adds a public member to a class.
  * CI-like builds run on a fresh output base with a warm disk cache, after an API edit.

Summed compile time is the sum of the KotlinCompile action durations in Bazel's JSON profile; the
IC modes also log one JSON record per compilation (--experimental_incremental_compilation_log).

Usage:
  ic_benchmark.py generate <workspace> --rules-kotlin <unpacked release dir>
  ic_benchmark.py run <config.json>

The config names the workspace, the target, the edits and the scenarios, see `generate`'s output
(`benchmark.json`) for an example. Edits are {"file": path, "anchor": text, "kind":
"implementation" | "api"}: the edit inserts a line after the first line containing the anchor,
which must sit inside a function body for an "implementation" edit and directly in a class body
for an "API" edit. In an existing workspace, add comment lines such as `// IC-BENCH-BODY` and
`// IC-BENCH-API` at those places as anchors.

Scenarios (--scenarios, comma separated): cold, incremental, progression (the cold, second and
third builds with lazy state), ci. To benchmark another workspace, e.g. a monorepo on a build VM:
point its rules_kotlin at this branch (a local_path_override of the unpacked
//:rules_kotlin_release), add to "flags" whatever keeps the builds local and uncached (e.g.
"--remote_cache=", "--bes_backend=", "--noremote_accept_cached"), and set "edits", "prime_edit"
(an API edit that recompiles the targets the incremental scenarios measure) and "ci_edit".
"""

import argparse
import gzip
import json
import os
import random
import shutil
import statistics
import subprocess
import sys
import time
import uuid

IC = "--worker_extra_flag=KotlinCompile=--experimental_incremental_compilation"
MODES = {
    "off": [],
    "lazy": [IC, IC + "_log"],
    "eager": [IC, IC + "_log", IC + "_state=eager"],
}


# --- Synthetic workspace -------------------------------------------------------------------------


def _function_bodies(name, count, rng):
    lines = []
    for f in range(count):
        a, b = rng.randint(1, 9), rng.randint(1, 9)
        lines += [
            f"  fun f{f}(x: Int): Int {{",
            f"    val values = (0 until x + {a}).map {{ it * {b} + id }}",
            f"    val grouped = values.groupBy {{ it % {a + 1} }}.mapValues {{ (_, v) -> v.sum() }}",
            f"    return grouped.values.fold({f}) {{ acc, v -> acc + v }} + \"{name}\".length",
            "  }",
        ]
    return lines


def generate(workspace, rules_kotlin, seed=7):
    """Writes a workspace shaped like a service closure: generated-code targets fed from source
    jars, small handwritten libraries, a shared core, mid-level libraries and a large top target."""
    rng = random.Random(seed)
    if os.path.exists(workspace):
        shutil.rmtree(workspace)
    os.makedirs(workspace)

    def write(path, text):
        full = os.path.join(workspace, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w") as f:
            f.write(text)

    write("MODULE.bazel", "\n".join([
        'module(name = "icbench")',
        'bazel_dep(name = "rules_kotlin", version = "2.2.0")',
        f'local_path_override(module_name = "rules_kotlin", path = "{rules_kotlin}")',
        'register_toolchains("//:kotlin_toolchain")',
        "",
    ]))
    write(".bazelversion", "9.3.0\n")
    # Worker and JDK settings taken from a large production monorepo.
    write(".bazelrc", "\n".join([
        "common --noworker_sandboxing",
        "build --worker_max_multiplex_instances=KotlinCompile=HOST_CPUS*0.5",
        "build --worker_max_instances=1",
        "build --worker_quit_after_build",
        "build --java_runtime_version=remotejdk_21",
        "build --java_language_version=21",
        "build --tool_java_runtime_version=remotejdk_21",
        "build --tool_java_language_version=21",
        "",
    ]))
    # Kotlin toolchain settings taken from a large production monorepo.
    build = [
        'load("@rules_kotlin//kotlin:core.bzl", "define_kt_toolchain")',
        'load("@rules_kotlin//kotlin:jvm.bzl", "kt_jvm_library")',
        "",
        "define_kt_toolchain(",
        '    name = "kotlin_toolchain",',
        '    api_version = "2.2",',
        "    experimental_build_tools_api = True,",
        "    experimental_multiplex_workers = True,",
        "    experimental_remove_debug_info_in_abi_jars = True,",
        "    experimental_remove_private_classes_in_abi_jars = True,",
        '    experimental_strict_kotlin_deps = "warn",',
        "    experimental_use_abi_jars = True,",
        '    jvm_target = "21",',
        '    language_version = "2.2",',
        ")",
        "",
    ]

    protos, libs, mids = 120, 60, 10
    import zipfile

    for p in range(protos):
        jar = os.path.join(workspace, "protos", f"p{p}.srcjar")
        os.makedirs(os.path.dirname(jar), exist_ok=True)
        with zipfile.ZipFile(jar, "w") as z:
            for m in range(15):
                z.writestr(f"gen/p{p}/Msg{m}.kt", "\n".join([
                    f"package gen.p{p}",
                    f"data class Msg{m}(val a: Int = 0, val b: String = \"\", val c: List<Long> = emptyList()) {{",
                    '  fun encode(): String = "$a:$b:" + c.joinToString(",")',
                    f"  companion object {{ fun decode(s: String): Msg{m} = Msg{m}(s.length, s, listOf(s.length.toLong())) }}",
                    "}",
                    "",
                ]))
        build += [f'kt_jvm_library(name = "proto_{p}", srcs = ["protos/p{p}.srcjar"])']

    core_files = []
    for c in range(60):
        lines = ["package core", "", f"class Core{c}(val id: Int) {{"]
        if c == 0:
            lines += ["  // EDIT-API"]
        body = _function_bodies(f"core{c}", 8, rng)
        if c == 0:
            body.insert(1, "    // EDIT-BODY")
        lines += body + ["}", ""]
        write(f"core/Core{c}.kt", "\n".join(lines))
        core_files.append(f"core/Core{c}.kt")
    build += ['kt_jvm_library(name = "core", srcs = glob(["core/*.kt"]))']

    for l in range(libs):
        used = rng.sample(range(protos), 3)
        deps = [f":proto_{u}" for u in used] + [":core"] + ([f":lib_{l - 1}"] if l else [])
        for f in range(5):
            write(f"libs/l{l}/L{l}F{f}.kt", "\n".join([
                f"package libs.l{l}",
                "",
                f"class L{l}F{f}(val id: Int) {{",
                *_function_bodies(f"l{l}f{f}", 4, rng),
                f"  fun use(): String = core.Core{(l + f) % 60}(id).f0(2).toString() + "
                + " + ".join(f"gen.p{u}.Msg{f}().encode()" for u in used),
                "}",
                "",
            ]))
        build += [f'kt_jvm_library(name = "lib_{l}", srcs = glob(["libs/l{l}/*.kt"]), deps = {deps!r})']

    for m in range(mids):
        used = rng.sample(range(libs), 6)
        deps = [":core"] + [f":lib_{u}" for u in used]
        for f in range(30):
            write(f"mids/m{m}/M{m}F{f}.kt", "\n".join([
                f"package mids.m{m}",
                "",
                f"class M{m}F{f}(val id: Int) {{",
                *_function_bodies(f"m{m}f{f}", 6, rng),
                f"  fun use(): String = libs.l{used[f % 6]}.L{used[f % 6]}F{f % 5}(id).use() + core.Core{f}(id).f1(3)",
                "}",
                "",
            ]))
        build += [f'kt_jvm_library(name = "mid_{m}", srcs = glob(["mids/m{m}/*.kt"]), deps = {deps!r})']

    for f in range(120):
        m = f % mids
        lines = ["package service", "", f"class S{f}(val id: Int) {{"]
        body = _function_bodies(f"s{f}", 6, rng)
        if f == 0:
            body.insert(1, "    // EDIT-BODY")
        lines += body + [f"  fun use(): String = mids.m{m}.M{m}F{f % 30}(id).use()", "}", ""]
        write(f"service/S{f}.kt", "\n".join(lines))
    build += [
        'kt_jvm_library(name = "service", srcs = glob(["service/*.kt"]), '
        f'deps = [":core"] + [":mid_%d" % m for m in range({mids})])',
        "",
    ]
    write("BUILD.bazel", "\n".join(build))

    config = {
        "workspace": workspace,
        "target": "//:service",
        "bazel": "bazelisk",
        "flags": [
            "--@rules_kotlin//kotlin/settings:experimental_build_tools_api=true",
            "--worker_quit_after_build",
        ],
        "edits": {
            "core_implementation": {"file": "core/Core0.kt", "anchor": "// EDIT-BODY", "kind": "implementation"},
            "core_api": {"file": "core/Core0.kt", "anchor": "// EDIT-API", "kind": "api"},
            "top_implementation": {"file": "service/S0.kt", "anchor": "// EDIT-BODY", "kind": "implementation"},
        },
        "repetitions": 3,
        "cold_modes": ["off", "lazy", "eager"],
        "incremental_modes": ["off", "lazy"],
        "ci_modes": ["off", "lazy", "eager"],
        "ci_edit": "core_api",
        "prime_edit": "core_api",
    }
    with open(os.path.join(workspace, "benchmark.json"), "w") as f:
        json.dump(config, f, indent=2)
    return config


# --- Running ------------------------------------------------------------------------------------


class Bench:
    def __init__(self, config, results_dir):
        self.config = config
        self.ws = config["workspace"]
        self.results_dir = results_dir
        self.output_base = config.get("output_base") or os.path.join(results_dir, "output_base")
        self.records = []
        self.counter = int(time.time())

    def bazel(self, output_base, *args, check=True):
        cmd = [self.config["bazel"], f"--output_base={output_base}", *args]
        start = time.monotonic()
        proc = subprocess.run(cmd, cwd=self.ws, capture_output=True, text=True)
        wall = time.monotonic() - start
        if check and proc.returncode != 0:
            sys.stderr.write(proc.stdout[-4000:] + proc.stderr[-8000:])
            raise SystemExit(f"failed: {' '.join(cmd)}")
        return wall, proc

    def edit(self, name):
        spec = self.config["edits"][name]
        self.counter += 1
        n = self.counter
        path = os.path.join(self.ws, spec["file"])
        with open(path) as f:
            lines = f.read().split("\n")
        index = next(i for i, line in enumerate(lines) if spec["anchor"] in line)
        indent = lines[index][: len(lines[index]) - len(lines[index].lstrip())]
        if spec["kind"] == "implementation":
            line = f"{indent}val icBenchMarker{n} = {n}"
        else:
            line = f"{indent}fun icBenchApi{n}(): Int = {n}"
        lines.insert(index + 1, line)
        with open(path, "w") as f:
            f.write("\n".join(lines))

    def ic_log(self, output_base):
        execroot = os.path.join(output_base, "execroot")
        if not os.path.isdir(execroot):
            return []
        for name in os.listdir(execroot):
            log = os.path.join(execroot, name, "bazel-out", "_kotlin_ic", "log.jsonl")
            if os.path.exists(log):
                with open(log) as f:
                    return [json.loads(line) for line in f if line.strip()]
        return []

    def build(self, scenario, mode, output_base=None, extra=()):
        output_base = output_base or self.output_base
        profile = os.path.join(self.results_dir, f"profile-{uuid.uuid4().hex[:8]}.json.gz")
        logged = len(self.ic_log(output_base))
        wall, proc = self.bazel(
            output_base, "build", "--noshow_progress", "--color=no", f"--profile={profile}",
            *self.config["flags"], *MODES[mode], *extra, self.config["target"],
        )
        with gzip.open(profile) as f:
            events = json.load(f)["traceEvents"]
        os.remove(profile)
        compiles = [e for e in events if e.get("ph") == "X" and (e.get("args") or {}).get("mnemonic") == "KotlinCompile"]
        ic = self.ic_log(output_base)[logged:]
        record = {
            "scenario": scenario,
            "mode": mode,
            "wall_s": round(wall, 2),
            "compile_s": round(sum(e["dur"] for e in compiles) / 1e6, 2),
            "compiles": len(compiles),
            "ic_modes": {m: sum(1 for r in ic if r["mode"] == m) for m in sorted({r["mode"] for r in ic})},
            "ic_phases_s": {
                phase: round(sum(r["phases_ms"].get(phase, 0) for r in ic) / 1000, 2)
                for phase in ["staging", "snapshots", "backup", "compile", "commit", "outputs"]
            },
        }
        self.records.append(record)
        print(json.dumps(record), flush=True)
        with open(os.path.join(self.results_dir, "records.jsonl"), "a") as f:
            f.write(json.dumps(record) + "\n")
        return record

    def cold(self):
        modes = self.config["cold_modes"]
        # A throwaway build: fetches, the first analysis, and file caches warming up.
        self.bazel(self.output_base, "clean")
        self.build("warmup", "off", extra=["--disk_cache="])
        for _ in range(self.config["repetitions"]):
            for mode in modes:
                self.bazel(self.output_base, "clean")
                self.build("cold", mode, extra=["--disk_cache="])

    def incremental(self):
        modes = self.config["incremental_modes"]
        ic_mode = next(m for m in modes if m != "off")
        # State for every target: with lazy state, a target compiled twice has it.
        for _ in range(2):
            self.edit(self.config["prime_edit"])
            self.build("prime", ic_mode, extra=["--disk_cache="])
        for name in self.config["edits"]:
            for _ in range(self.config["repetitions"]):
                for mode in modes:
                    self.edit(name)
                    self.build(name, mode, extra=["--disk_cache="])
            # The state of the edited target after its last off build is a compilation behind.
            self.edit(name)
            self.build("prime", ic_mode, extra=["--disk_cache="])

    def progression(self):
        """With lazy state: the cold build, the second build (which creates the state of the targets
        it compiles) and the third build (the first incremental one), each after an API edit."""
        for _ in range(self.config["repetitions"]):
            for mode in ("off", "lazy"):
                self.bazel(self.output_base, "clean")
                self.build("build_1_cold", mode, extra=["--disk_cache="])
                for build in ("build_2", "build_3"):
                    self.edit(self.config["prime_edit"])
                    self.build(build, mode, extra=["--disk_cache="])

    def ci(self):
        cache = os.path.join(self.results_dir, "disk_cache")
        warm = os.path.join(self.results_dir, "ci_warm")
        self.build("ci_warm", "off", output_base=warm, extra=[f"--disk_cache={cache}"])
        self.bazel(warm, "shutdown")
        shutil.rmtree(warm, ignore_errors=True)
        for _ in range(self.config["repetitions"]):
            for mode in self.config["ci_modes"]:
                self.edit(self.config["ci_edit"])
                fresh = os.path.join(self.results_dir, f"ci_{uuid.uuid4().hex[:8]}")
                self.build("ci", mode, output_base=fresh, extra=[f"--disk_cache={cache}"])
                self.bazel(fresh, "shutdown", check=False)
                subprocess.run(["chmod", "-R", "u+w", fresh], capture_output=True)
                shutil.rmtree(fresh, ignore_errors=True)

    def summary(self):
        rows = {}
        for r in self.records:
            if r["scenario"] in ("prime", "ci_warm", "warmup"):
                continue
            rows.setdefault((r["scenario"], r["mode"]), []).append(r)
        lines = ["| scenario | mode | runs | wall s (median) | compile s (median) | KotlinCompile actions |", "|---|---|---|---|---|---|"]
        for (scenario, mode), rs in rows.items():
            lines.append(
                f"| {scenario} | {mode} | {len(rs)} | {statistics.median(x['wall_s'] for x in rs):.1f} | "
                f"{statistics.median(x['compile_s'] for x in rs):.1f} | {statistics.median(x['compiles'] for x in rs):.0f} |"
            )
        text = "\n".join(lines)
        with open(os.path.join(self.results_dir, "summary.md"), "w") as f:
            f.write(text + "\n")
        print(text)


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    g = sub.add_parser("generate")
    g.add_argument("workspace")
    g.add_argument("--rules-kotlin", required=True)
    r = sub.add_parser("run")
    r.add_argument("config")
    r.add_argument("--results", required=True)
    r.add_argument("--scenarios", default="cold,incremental,ci")
    args = parser.parse_args()
    if args.command == "generate":
        generate(os.path.abspath(args.workspace), os.path.abspath(args.rules_kotlin))
        return
    with open(args.config) as f:
        config = json.load(f)
    os.makedirs(args.results, exist_ok=True)
    bench = Bench(config, os.path.abspath(args.results))
    for scenario in args.scenarios.split(","):
        getattr(bench, scenario)()
    bench.summary()


if __name__ == "__main__":
    main()
