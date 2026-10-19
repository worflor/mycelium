# AGENTS.md: Mycelium

Repository rules and navigation for contributors and agents. Start with [README.md](README.md); use the map below
to find the technical detail needed for a change.

## What Mycelium is

**NAML: Not A Mod Loader.** Mycelium is a build-time compatibility compiler developed alongside a Minecraft mod. It
introduces no shared runtime and replaces no loader. A divide (something true on one version
or loader and different on another) becomes a declaration, laws with a rejection matrix, era data, build-time compilers
that emit per-target artefacts, gates, and evidence in the jar. Everything a mod imports becomes that mod's own private
code. The model of it is `docs/architecture.md`.

## The posture: build with it

The mod's author structures the mod around Mycelium and defines the contracts its features need; the compiler
supplies the shared compatibility machinery (`docs/building-with-mycelium.md`).
Existing contracts are optional reuse and worked examples. A missing contract or realization is developed with the
feature in the Mycelium checkout the mod builds with, which may be a private fork. Upstream issues and pull requests
are ways to contribute that work, not prerequisites for doing it.

When the mod you are working on meets a divide (a `//? if` that carries meaning, a fact written twice, a lifecycle the
loaders expose differently), do not hand-write the split in the mod and do not bend the mod to fit a package that models
something else. Cross it in that Mycelium checkout: declare it, state the laws, write the broken implementations,
put version knowledge in `eras/`, compile the artefacts, and use the result in the mod in the same piece of work.
One mod is enough to start.
The playbook is `docs/building-with-mycelium.md`. The decision behind it is
`docs/decisions/0015-a-compiler-you-build-with.md`.

Consumers pin their own contract versions. Preserve an existing major's meaning and run the gates before handing
off a change.

## Where knowledge lives

Each fact has one home. Everything else links to it.

| Kind of knowledge | Home |
|---|---|
| What Mycelium is, how to start, where to go | `README.md` |
| Onboarding, routing, rules for working here | `AGENTS.md` (this file) |
| The technical model, principles, what checks what | `docs/architecture.md` |
| What to do when a mod meets a divide | `docs/building-with-mycelium.md` |
| Agent workflow for a fresh mod or adoption | `.agents/skills/building-with-mycelium/SKILL.md` |
| What makes a good addition; rules for documents | `docs/admission.md` |
| What a contract package is made of | `CONTRACT-PACKAGE.md` |
| What a bridge package is made of | `BRIDGE-PACKAGE.md` |
| One contract's meaning and laws | its `SPEC.md` in `contracts/<slug>/` |
| Worked examples of crossings | `contracts/README.md` |
| Every contract's id, path, package | `contracts/registry.json` (checked; the only such mapping) |
| Versions, eras, the pure layer's laws and gates | `docs/eras.md`, data in `eras/` |
| The plugin: options, tasks, shipping a jar | `docs/plugin.md` |
| Pinning, editing and updating a consumer's compiler | `docs/source-lifecycle.md`, maintained wrapper in `source-wrapper/` |
| Optional integrations kept isolated | `docs/integrations.md` |
| Why a choice was made | `docs/decisions/` (one per file, dated, with its evidence) |
| What changed and what ran | `CHANGELOG.md` |
| Open work, proposals, warnings | `docs/STATUS.md` |
| Durable lookup material | `docs/reference/` |
| The founding design (v0.4, frozen) and earlier versions | `DESIGN.md`, `archive/` |

Working notes stay out of the repository until a finding earns a home: a law in a SPEC, a decision, a check, a script.
`DESIGN.md` is the founding record: read its §0, §1, §5 and §11 for the thesis, the tower and the claims discipline, the
rest on demand. Where a decision goes beyond it, the decision is current.

## Map

```
mycelium/
├── README.md, AGENTS.md    the front door, and this file
├── DESIGN.md               founding design, v0.4, frozen (archive/ holds earlier versions)
├── CONTRACT-PACKAGE.md     what a contract package is made of, and what keeps each rule
├── BRIDGE-PACKAGE.md       what a bridge package is made of (the native half of a divide), and what keeps each rule
├── CHANGELOG.md            history: what changed, what ran, what was found
├── docs/                   architecture, building-with-mycelium, admission, eras, plugin, integrations, STATUS,
│   │                       decisions/ (why), reference/ (lookup)
├── eras/
│   ├── minecraft.json      every version by era: Java, loaders, names, walls
│   ├── loaders.json        each loader's packages (and the game's); the pure layer may mention none
│   ├── versions.json       every real version, from Mojang's launcher manifest
│   └── names.json          pinned name data per naming and version
├── harness/
│   ├── sources.txt         what the harness is compiled from; every runner reads it
│   ├── src/                Check, Suite, ContractRun (the rules every matrix keeps), Seeded
│   └── test/               the harness's own checks, each a main; every runner runs them first
├── contracts/
│   ├── README.md           the worked examples
│   ├── registry.json       id, path, package, status, supersedes, provenance
│   ├── patterns.json       code copied between packages on purpose, kept identical
│   └── <slug>/             one package per contract (CONTRACT-PACKAGE.md)
├── bridges/<slug>/         one package per bridge: bridge.json, a src/ per variant, overlays/, conformance/
├── gradle-plugin/          the plugins, an included build (map below)
├── source-wrapper/         Java 17 source lifecycle, consumer hooks and reproducible bundle (built by gradle-plugin)
├── run-contracts.sh, .ps1  the harness without Gradle: identical behaviour
└── build.gradle.kts        applies both plugins to this repository; `./gradlew check` runs everything
```

### The plugin, class by class

`gradle-plugin/src/main/java/dev/woflo/mycelium/gradle/`. The repository check fails if a class is missing here. What
the tasks do is `docs/plugin.md`.

- **Wiring.** `MyceliumPlugin.java` (`dev.woflo.mycelium`, for consumers: registers the tasks, wires them into `check`
  and the jar that ships, derives the contract list), `MyceliumDefaultsPlugin.java` (`dev.woflo.mycelium.defaults`: the
  `myceliumDefaults { }` block on a root project that every project inherits), `MyceliumExtension.java` (the
  `mycelium { }` block), `PureSpec.java` (`pure { }`), `RangeSpec.java` (`range { }`), `LinkageSpec.java`
  (`range { linkage { } }`), `CapsuleSpec.java` (the capsule's ceilings, nested jars and mapping profile).
- **Check levels.** `Checks.java` (every check, its default level, allowances and findings; decision 0017),
  `ChecksSpec.java` (`checks { }`).
- **Generated from the era table.** `PackMetaSpec.java` (`packMeta { }`), `PackMetaTask.java` (`myceliumPackMeta`),
  `OverlaySpec.java` (`overlays { }`: a resource directory per schema era).
- **Proofs a mod takes from Mycelium.** `Proofs.java` (`contracts/proofs.json`: fingerprints of what Mycelium's own
  check proved), `ProofRecordTask.java` (`myceliumRecordProofs`, this repository only).
- **Contracts.** `ContractRegistry.java` (reads `contracts/registry.json` strictly; resolves selections and
  `supersedes`), `DeriveContractsTask.java` (`myceliumDeriveContracts`), `ImportContractsTask.java` (`myceliumImport`), `VerifyContractsTask.java` (`myceliumVerify`),
  `Harness.java` (what every runner shares: the harness's sources, and the bundle an era gate runs),
  `UsageTask.java` (`myceliumUsage`).
- **Bridges** (decision 0016). `Bridges.java` (reads `bridges/<slug>/bridge.json` strictly; selects a variant and its overlays
  for a version, rewrites packages under the mod's namespace, lays the files out), `BridgesSpec.java` (including what conformance data a jar ships)
  (`bridges { use skip override conformance }`), `ImportBridgesTask.java` (`myceliumBridgeImport`), `BridgeCheckTask.java`
  (`myceliumBridges`: an override implements the port (B1), a bridge has a realization on the target (B2)), `BridgeCheck.java`
  (this repository's check of every bridge: rows cover each version once, overlays lie inside their variant),
  `BridgeLinkage.java` and `BridgeLinkageTask.java` (`myceliumBridgeLinkage`: every variant with each overlay compiled against
  the game and linked against every version it covers that has name data), `GameClasspath.java` (a pinned game archive
  renamed to Mojang names, shape only, as the compile classpath).
- **The pure layer and integrations.** `PurityTask.java` (P1 and P3), `PureCompileTask.java` (the pure layer compiled
  alone, into a bundle), `IsolationTask.java` (`myceliumIsolation`; `docs/integrations.md`).
- **Era data.** `Eras.java` (the era table: bounds, lookup, coverage, walls, names, scanner Java), `Versions.java`
  (`eras/versions.json`), `Loaders.java` (`eras/loaders.json`), `CompatExpr.java` (the loaders' version-range syntaxes,
  read as a predicate and written from a range), `CompatMetadata.java` (where a built artifact declares the versions it
  accepts).
- **Era gates.** `EraTask.java` (`myceliumEraJava<N>`, and `myceliumEras`), `Linkage.java` (law P2: every outside
  reference in a bundle exists on the era's Java).
- **Range and linkage.** `ArtifactLedger.java` (a consumer's `artifacts.json`), `RangeGate.java` and `RangeTask.java`
  (`myceliumRange`), `LinkageGate.java` and `LinkageTask.java` (`myceliumLinkage`), `References.java` (every class,
  method and field an artifact's class files reach, inherited members traced), `NameData.java` (`eras/names.json`,
  strictly read), `NameCache.java` (pinned files by SHA-256, fetched once, checked on every use), `Names.java` (what the
  game has under one naming on one version), `Inventory.java` (the game's own classes, resolving a member through its
  hierarchy as the JVM does).
- **Behavioral diagnostics.** `BehaviorTask.java` (`myceliumBehavior`, opt-in), `CallbackInitialization.java`
  (callback targets, reference provenance and initialization flow; potential risks, not native event-order proofs).
- **Presentation.** `Presentation.java` (what `myceliumPresentation` and `myceliumPresentationLock` do apart from
  Gradle: reads a consumer's programs and mosaics, compiles `item.presentation@1`'s and `block.presentation@1`'s models with what they require, runs their tools),
  `PresentationSpec.java` (`mycelium { presentation { } }`), `PresentationTask.java` (`myceliumPresentation`: the
  programs compiled for the target's realization into generated resources), `PresentationLockTask.java`
  (`myceliumPresentationLock`: appends the legacy codes the programs need to the committed lock, on request).
- **Shipping a jar.** `PureShipTask.java` (`myceliumPureShip`), `DowngradeJarTask.java` (`myceliumDowngradeJar`),
  `DowngradedMetadata.java` (the required annotation API closure, under the artifact's private prefix),
  `PrivateLibraries.java` (package relocation for declared feature libraries), `SurfaceTask.java` (`myceliumSurface`),
  `CapsuleTask.java` (`myceliumCapsule`), `RuntimeBootClasspath.java` (an optional buildscript helper that resolves
  ordered Java 8 bootstrap roots from a toolchain launcher).
- **Evidence.** `EvidenceTask.java` (`myceliumEvidence`, artifact prerequisites), `VerificationEvidenceTask.java`
  (`myceliumVerificationEvidence`, external reports and finished archive hashes), `TaskOutcomes.java` (build-scoped
  producer completion facts), `Fingerprint.java` (stable SHA-256 over files and directories, for inputs and evidence).
- **This repository only.** `MyceliumRepositoryPlugin.java` (`dev.woflo.mycelium.repository`), `RepositoryCheck.java`
  (the repository against what it states), `LawCoverage.java` (every law label a harness asserts has a rejection-matrix entry or a reasoned
  allowance in the package's `unmutated.json`), `RepositoryCheckTask.java` (`myceliumRepository`),
  `RefreshVersionsTask.java` (`myceliumRefreshVersions`), `RefreshNamesTask.java` (`myceliumRefreshNames`),
  `NameRefresh.java` (what the refresh does apart from the task).

The maintainer corpus collector and its offline tests live in `tools/`; the bytecode lab is a plugin test tool.
See [inspection checks](docs/plugin.md#inspecting-an-existing-mod) for its pinned inputs and limits.

The self-tests, `gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java`, break a scratch copy of the
repository in each way a check exists for, and prove the check names the break.

## Hard rules

1. Contract models, fixtures and the harness never touch Minecraft or a loader. If a declaration needs a Minecraft type,
   that part is a provider's work.
2. `fixtures/` and `harness/` never ship. `myceliumImport` copies `model/` only, and refuses a model that reaches the
   harness.
3. A model mentions no other contract's package, except the `model` package of one its registry row requires (exact ids,
   acyclic, none retired; `docs/decisions/0014-contracts-compose-by-declared-requirement.md`). Otherwise contracts compose in the
   mod (`docs/decisions/0005-what-a-contract-is-made-of.md`).
4. Labels are a contract's public surface. Renaming one, or changing a law, an outcome or a required method, is a new
   major (`CONTRACT-PACKAGE.md`, rule 12).
5. The plugin never gains loader-specific logic. Loader knowledge is data (`eras/`) or a mod's buildscript.
6. `contracts/registry.json` is the only place a contract's id, path and package are declared together.
7. A passing harness is design-time evidence, never a claim about the game; never weaken a law or delete a matrix entry
   to make your own work pass (`docs/architecture.md`, Principles).
8. `DESIGN.md` is frozen. Decisions that go beyond it are recorded in `docs/decisions/`.

## How to change Mycelium

Every loop ends the same way: `./gradlew check` green, and a `CHANGELOG.md` entry saying what changed and what ran.

- **Cross a divide.** `docs/building-with-mycelium.md`. For the package itself: `SPEC.md` first, then `model/`, then
  `fixtures/` (the reference and every broken implementation, a `NoOp*` among them), then the harness
  (`CONTRACT-PACKAGE.md`), and the registry row and `contracts/README.md` row in the same change. Iterate with
  `bash run-contracts.sh <slug>` until the reference is clean and every entry is `CAUGHT`.
- **Extend a contract.** A law a mod found that the contract never stated is usually one any implementation could break:
  add the law, a label, and a fixture that makes the mistake, and say in the SPEC, in words, where it was learned.
  Additive changes stay in `@N`.
- **Change a contract's meaning.** That is a new major: a new package directory, `supersedes` in its row. Existing
  consumers keep the old one until they move.
- **Change the plugin.** Keep it loader-blind. Add a self-test that breaks what the change checks. Run `./gradlew check`
  here, then at least one consumer's build, which is the independent proof. A new task or option meets
  `docs/admission.md`, and `docs/plugin.md` says what it does.
- **Add an era, a loader or a version.** Edit `eras/minecraft.json` or `eras/loaders.json` with sources, or run
  `./gradlew myceliumRefreshVersions` when a version ships. The repository check proves the table still covers every
  version exactly once. Name data is pinned with `myceliumRefreshNames` (`docs/eras.md`).
- **Write or change a bridge.** `BRIDGE-PACKAGE.md`, "Write a bridge": the registry row's `bridges`, the realization rows in
  `eras/minecraft.json`, `myceliumRefreshNames`, the cases written against the port. Run `./gradlew myceliumBridgeLinkage`
  (and `myceliumRepository`) and a consumer fixture.
- **Change the harness.** It is every contract's evidence. Prove the rule in `harness/test` and run every package.
- **Change a document.** Follow `docs/admission.md`, "A document". A decision gets its own record: copy the structure of
  `docs/decisions/0001-substrate.md` (date, status, context, options, decision, evidence, consequences).

## Scope for autonomous agents

You may, without asking:
- change `contracts/`, `harness/`, `gradle-plugin/`, `eras/`, `docs/` and the living documents, within the rules above;
- cross a divide end to end, with its registry row, its use in the mod, and its entry in `CHANGELOG.md`;
- fix a plugin bug, with a self-test that proves the fix;
- run every gate and runner.

Ask first, because these are irreversible or reach another consumer:
- changing an existing package's meaning in place (a new major is the default answer);
- adding a dependency (Gson and ASM in the plugin; nothing in contracts or the harness);
- publishing, tagging, or anything destructive outside this folder;
- touching another repository. A consumer's repository is read-only from here unless its owner has said otherwise; read
  it to understand a need, and change it from its own repository.

## Gotchas

| Area | Issue | Mitigation |
|---|---|---|
| Posture | Reading `contracts/` as a shelf, then hand-writing the split in the mod | The mod met a divide: cross it here (`docs/building-with-mycelium.md`) |
| Claims | A passing harness is tempting to read as "works in Minecraft" | It proves the model and the matrix. Game evidence is the mod's |
| No-ops | Safety laws alone let a do-nothing implementation pass | Every matrix has a `NoOp*` entry; `ContractRun` fails a matrix without one |
| Package names | Slugs are kebab-case; Java packages cannot be | The registry's `package` is the only mapping; never recompute it |
| Transactions | A new transaction shape invites undo-order and nested-commit bugs | Copy `FlowTransaction` and rename it; `contracts/patterns.json` keeps the copies identical |
| Coupling | Adding a Minecraft type to a model "just this once" | It breaks purity and the no-runtime rule. The mod's seam converts |
| Shared vocabulary | Ten `Reason` enums look like duplication | They share a name, not a meaning. Each is its own contract's (decision 0005) |
| Vacuous passes | A gate given nothing to check can look green | Every report says what it checked; evidence carries `"checked": false` with a `why` |
| Old versions | A build that compiles is not a port | A rung is ported when its whole suite runs there (`docs/reference/porting-lessons.md`) |

## Commands

```bash
./gradlew check
./gradlew myceliumRepository
./gradlew myceliumBridgeLinkage
./gradlew myceliumVerify -Pmycelium.contracts=world.copy@1
./gradlew -p gradle-plugin selfTest
bash run-contracts.sh flow world-copy
./gradlew myceliumRefreshVersions
./gradlew myceliumRefreshNames --namespace=mojang --versions=1.21.2
```

On a machine shared with other JVMs (a game server, say), keep the daemon's heap small
(`./gradlew check -Dorg.gradle.jvmargs=-Xmx512m --max-workers=1`), run one Gradle build at a time, and never stop a daemon
that is not yours. `GRADLE_OPTS` alone limits the launcher, not the daemon heap set in `gradle.properties`.

---

*When a contract's behaviour disagrees with its SPEC, the SPEC changes and the reason is written down: it is normative
because it is kept honest. When `DESIGN.md` doesn't say, that is a decision to make and record, not to guess past.*
