---
name: building-with-mycelium
description: Plan or implement a Minecraft mod with Mycelium, either as a fresh mod or by adopting it in an existing codebase. Use when structuring compatibility boundaries, choosing Java targets, defining contracts, or integrating Mycelium's build compiler.
---

# Building with Mycelium

Use this skill when building a mod around Mycelium or bringing Mycelium into a mod that already exists. Start from the user's requested outcome and choose the relevant path below. Keep the mod's feature meaning, APIs, and user-visible behavior intact while making compatibility work explicit.

Mycelium is a build-time compiler that a mod develops alongside, in a separate checkout or fork. It adds no shared runtime and does not replace a loader. The mod owns its feature behavior, seams, and native providers; Mycelium supplies contract imports, bridge variants, target data, generators, gates, and evidence. Native providers retain access to full platform APIs. Read [the architecture](../../../docs/architecture.md) when deciding layer boundaries and [decision 0015](../../../docs/decisions/0015-a-compiler-you-build-with.md) for the compiler posture.

## Choose a path

### Fresh mod

1. Establish the actual target versions and loaders from the request. Consult the canonical [era and realization data](../../../eras/minecraft.json), [loader data](../../../eras/loaders.json), and the explanation in [docs/eras.md](../../../docs/eras.md). When a requested target is missing, inspect its native platform and extend the data and realizations with evidence. A table row alone does not prove the mod works there.
2. Set up the consumer source wrapper from the [source lifecycle guide](../../../docs/source-lifecycle.md), then apply `dev.woflo.mycelium` and set `root`, an artifact-owned `namespace`, and `minecraft` in `mycelium { }`. Use the consumer template and lifecycle guide for exact setup; the plugin reference covers the `mycelium { }` block.
3. Keep pure feature logic and typed domain behavior away from native Minecraft and loader types. Put native calls in per-platform providers; let seams compose intent with providers. Preserve direct use of native APIs for capabilities that have no suitable contract.
4. For each real divide, decide whether an existing contract has the same meaning. Reuse it only when it fits; otherwise define the feature's contract, laws, broken implementations, and `NoOp` in this Mycelium checkout. For game-facing behavior, add or use a bridge and realization data. Do not hand-maintain a version switch or duplicate generated facts in the mod.
5. Use generated outputs and checks that match the feature: contract imports and verification, bridge import and conformance, presentation or pack metadata generation, era Java gates, range/linkage checks, then the mod's own gametests and runtime checks. A model harness proves the declared laws; behavior in the game needs game evidence.

### Existing codebase / adoption

1. Record the baseline before moving boundaries: build tasks and artifacts, supported loader/version matrix, current source sets and conditional compilation, public APIs, entrypoints, resources, and the existing test and gametest results. Run the user's existing validation where feasible so regressions can be compared with this baseline.
2. Inspect the real project layout and framework wiring, including remapping, mixins, generated sources, resource processing, packaging, and loader-specific entrypoints. Find places where behavior is duplicated or split by version/loader and determine what each split means. Preserve the current loader/framework setup and use its native APIs directly where that remains the clearest implementation.
3. Introduce Mycelium alongside the existing build in a small, useful boundary. First connect the build and set its target data. Then extract a pure feature seam or import a semantically matching contract; keep native adapters in the existing platform layer. Do not rewrite a framework just to fit Mycelium, and do not stop at copying helpers into a compatibility folder while leaving the same decisions duplicated.
4. Make each adoption change carry a concrete guarantee checked by the configured Mycelium gates or the mod's own tests. Only call behavior a contract when it states domain meaning and laws that a no-op breaks. Keep native functionality available even where no contract or bridge models it.
5. Launch the normally packaged artifact through its loader's production entrypoint and compare its behavior with the baseline. Run the mod's relevant game tests on each claimed loader/target, including bridge conformance cases where used. A successful compile or model harness alone does not establish that the production entrypoint loads or that the behavior works in game.

Use the [inspection checks](../../../docs/plugin.md#inspecting-an-existing-mod) to identify platform coupling,
unavailable bridge realizations and missing target APIs before writing manual compatibility rules. The opt-in
`myceliumBehavior` experimental linter derives potential callback initialization dependencies from compiled code.
Read each finding's inputs and scope. A missing API reference and an initialization-order problem require different evidence; follow the
bridge's lifecycle requirements and exercise cold startup and resource loading as well as a running world.

## Keep Java decisions separate

Do not collapse these into a single “Java version” setting:

- **Gradle host JVM:** Mycelium's Gradle plugin is Java 17 bytecode, and the repository guidance requires JDK 17 or newer to run Gradle. This is the JVM running the build.
- **Pure authored release:** `mycelium.pure.release` declares the Java release for the pure layer's source and API. It defaults to 17. Mycelium compiles this layer alone using the Gradle JVM when capable, otherwise a compiler toolchain for the release. Older era gates downgrade the resulting bytecode and check stubs, linkage, and suite behavior; this is conditional proof for that pure bundle, not a promise that arbitrary Java code or APIs can be backported.
- **Target runtime and platform toolchain:** The selected Minecraft version's Java comes from `eras/minecraft.json`; `mycelium.javaFor(version)` exposes it to a consumer buildscript. Configure the platform/game toolchain and dependencies for that target. Inspect the [era table](../../../docs/eras.md#the-platform-layer-the-map) for supported runtimes. An existing project authored for Java 7, 8 or 16 can be a migration source; its original Java release does not determine the destination runtime. Older source can often be compiled by a newer compiler, but removed APIs, dependencies and build tools still need inspection. Do not infer a Java 7 runtime guarantee from the ability to read or migrate Java 7 source.

Source syntax and API availability must also fit the compiler and platform dependencies used for each target. A Java 17 pure release may be gate-checked on older era JVMs through the configured downgrade path; that says nothing by itself about compiling a loader-specific provider against an old game's API. The `outerJava` field is a loader scanner's class-file ceiling for capsules, not the target runtime Java or the pure release. Use `mycelium.outerJavaFor(loader, version)` only for that capsule setting.

For a target, set the exact `mycelium.minecraft` and consult its era row for Java, loaders, naming, and walls. Use `mycelium.eras(...)` or `allEras()` only when deliberately checking additional runtimes. The gates can report exactly what they checked; preserve skipped or unavailable evidence as such rather than claiming support.

## Source of truth and handoff

Keep each maintained fact in its canonical home and link to it: [era and realization data](../../../eras/minecraft.json), [loader data](../../../eras/loaders.json), [contract registry](../../../contracts/registry.json), [building playbook](../../../docs/building-with-mycelium.md), [contract admission criteria](../../../docs/admission.md), [plugin tasks and options](../../../docs/plugin.md), and [porting lessons](../../../docs/reference/porting-lessons.md). Contract/package format belongs in [CONTRACT-PACKAGE.md](../../../CONTRACT-PACKAGE.md), bridge format in [BRIDGE-PACKAGE.md](../../../BRIDGE-PACKAGE.md). Check the live registries and guides before using an API or claiming a target; do not maintain a second contract inventory in this skill.

For compiler-side work, work in the Mycelium checkout or fork included by the mod, and coordinate changes with the consumer repository's owner. Keep the mod's build and game evidence in the consumer repository. Follow repository instructions and the playbook's gates and recordkeeping when changing Mycelium itself.

For wrapper installation, capture, source checks, and updates, follow the canonical [source lifecycle guide](../../../docs/source-lifecycle.md).
