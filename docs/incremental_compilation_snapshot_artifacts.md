# Design: classpath snapshots as Bazel artifacts

Status: proposal, not implemented.

## Problem

Incremental compilation (IC) compares each compile's classpath with the previous compile's
through *classpath snapshots*: one per classpath entry, which the Build Tools API (BTA) computes by
reading the jar. Today the worker computes them itself and shares them through
`bazel-out/_kotlin_ic/classpath-snapshots`, keyed by the entry's digest, the BTA runtime and the
snapshot granularity. Measured on the backend monorepo, 1,303 snapshots took 137 MB.

Each machine, and each fresh output base, computes every snapshot again:

* With lazy state (the default), cold builds compute none, but a target's second compile, the one
  that creates its state, snapshots its whole transitive classpath. For an API edit in a low-level
  target with 313 dependents, the second build snapshots about 1,300 jars.
* A dependency bump, such as a new Maven version or a new BTA runtime, invalidates the snapshots of
  every changed jar on every machine.

Every machine gets the same snapshot from the same jar. That is a cacheable action output, and
Bazel already knows how to compute those once and share them.

## Proposal

Make each classpath entry's snapshot a declared output of a small action, behind a build setting
that makes the snapshots inputs of `KotlinCompile`.

### Actions

* **`KotlinClasspathSnapshot`**: one action per compile jar. Its inputs are the jar and the BTA
  runtime jars; its output is `<target>/_kt_ic/<jar basename>.snapshot`. It runs in the
  `KotlinCompile` worker through a new builder task that calls `KotlinBtapiCompiler.snapshotClasspathEntry`.
  Its command line carries the granularity: class level for external repositories, member level
  otherwise, as today.
* **Kotlin targets** snapshot their own compile jar: the ABI jar when ABI jars are on, the class jar
  otherwise. This is a separate action rather than part of `KotlinCompile`, so that it does not
  lengthen the compile action, runs in parallel with dependents' other inputs, and can run remotely.
* **Other JVM targets** (`java_import` from `rules_jvm_external`, `java_library`, proto rules) get
  their snapshots from an aspect on the `deps`, `exports`, `associates` and `plugins` attributes of
  the Kotlin rules. The aspect creates a `KotlinClasspathSnapshot` action for each jar in
  `JavaInfo.compile_jars` and propagates a `KtClasspathSnapshotInfo(snapshots = depset)` provider.
  Aspect actions run only when an action consumes their outputs.

### Wiring into compilation

With `--@rules_kotlin//kotlin/settings:experimental_ic_classpath_snapshots=true`:

* `KotlinCompile` takes the snapshots of its compile classpath as inputs and passes
  `--classpath_snapshot=<jar>=<snapshot>` for each entry, through the existing param file.
* The worker uses the given snapshot instead of computing one, linking it into the shared snapshot
  directory so that compiles without the setting reuse it. Entries without a snapshot, such as
  directories, fall back to the current per-compile computation.
* The snapshot file carries a header with the format version, the BTA runtime key and the jar's
  digest. The worker checks the header against the request's digests and recomputes the snapshot
  if they differ, so a mismatch costs time, never correctness.

The setting changes the configuration, so it must be the same on CI and on developer machines for
them to share cache entries. Being part of the configuration also keeps it out of the worker
startup flags, which deliberately leave action keys alone.

### Why declared inputs, not a side channel

The alternative is to keep `KotlinCompile`'s inputs unchanged and have developers request a
transitive output group, so the worker would find snapshots by a path convention. That keeps action
keys stable, but:

* An undeclared file is only present if something downloaded it. Under Build without the Bytes,
  output groups of transitive targets are not downloaded unless requested at the top level, and
  remote execution never sees them.
* The worker cannot derive the snapshot path from a jar path. An aspect output lives under the
  package of the target that owns the jar, not next to the jar; for Maven jars that is a different
  directory from the jar's external repository path. The mapping would need its own channel.

Declared inputs make the snapshots part of the action graph: Bazel fetches them, caches them, and
invalidates them.

## Costs

* **Actions and analysis:** about one extra action per compile jar in the closure. For the
  `messenger` closure that is about 1,450 Kotlin targets and 1,300 other jars, so about 2,750
  actions. Each declares one output; analysis adds one aspect evaluation per JVM dependency.
* **CI compute:** CI computes a snapshot once per distinct jar content. Maven jars and generated
  protos rarely change, so after the first build CI recomputes only the snapshots of targets whose
  ABI changed, at roughly 10–300 ms each and off the critical path. On the cold full closure this is
  about 2,750 actions × ~0.1 s ≈ 4–5 min of CPU, spread across the build.
* **Remote cache storage:** about 137 MB for the closure's snapshots per BTA runtime, plus one
  ~100 KB entry per changed ABI per CI build. A BTA runtime upgrade writes a new set.
* **Keys:** turning the setting on changes every `KotlinCompile` key once. Snapshots are keyed by
  their action's inputs, the jar and the BTA runtime jars, so a runtime upgrade invalidates them
  without extra bookkeeping.

## Benefits

* The build that creates a target's state downloads its snapshots instead of computing them. This
  removes the snapshot phase from second compiles and from the first incremental build after a
  dependency bump.
* Fresh output bases, such as new checkouts or CI-like developer environments, start with every
  snapshot cached remotely.
* The local `classpath-snapshots` directory becomes a cache of artifacts rather than the only copy,
  so pruning it costs nothing.

## Plan

1. Builder task and `KotlinClasspathSnapshot` action, with header validation, behind the setting.
   Test: snapshots from the action and from the worker are byte-identical.
2. Aspect for non-Kotlin dependencies; `KotlinCompile` inputs and the `--classpath_snapshot` flag.
   Analysis tests: the flags, the inputs, and no change with the setting off.
3. Turn the setting on in CI, then on developer machines. Measure with
   `--experimental_incremental_compilation_log`: the `snapshots` phase of `full` compiles should
   drop to near zero.

## Open questions

* Whether to snapshot ABI jars only. A target compiled against a class jar, such as a friend's
  through `associates`, would need the class jar's snapshot.
* Whether member-level snapshots for external repositories pay for their size. Today's class-level
  choice for `external/` is a heuristic.
