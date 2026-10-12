# AGENTS.md: Mycelium

How to work in this repository. It is written for AI agents and human contributors alike, and it orients and routes:
the depth lives in the documents it points to. There is no `CLAUDE.md`.

## What Mycelium is

**NAML: Not A Mod Loader.** Mycelium is a compatibility compiler a Minecraft mod builds with. It ships no runtime and
replaces no loader; it lives between versions and loaders, at development time. A divide (something true on one version
or loader and different on another) becomes a declaration, laws with a rejection matrix, era data, build-time compilers
that emit per-target artefacts, gates, and evidence in the jar. Everything a mod imports becomes that mod's own private
code. The model of it is `docs/architecture.md`.

## The posture: build with it

Mycelium is live and grows with the mods that use it. `contracts/` holds worked examples, not a catalogue to choose from.
When the mod you are working on meets a divide (a `//? if` that carries meaning, a fact written twice, a lifecycle the
loaders expose differently), do not hand-write the split in the mod and do not bend the mod to fit a package that models
something else. Cross it here: declare it, state the laws, write the broken implementations, put version knowledge in
`eras/`, compile the artefacts, and use the result in the mod in the same piece of work. One mod is enough to start.
The playbook is `docs/building-with-mycelium.md`. The decision behind it is
`docs/decisions/0015-a-compiler-you-build-with.md`.

Several mods, by different people and agents, build on Mycelium at once. The gates are what make a change safe, so run
them and trust them; a change to an existing package's meaning in place is the one thing that reaches every consumer.

## Where knowledge lives

Each fact has one home. Everything else links to it.

| Kind of knowledge | Home |
|---|---|
| What Mycelium is, how to start, where to go | `README.md` |
| Onboarding, routing, rules for working here | `AGENTS.md` (this file) |
| The technical model, principles, what checks what | `docs/architecture.md` |
| What to do when a mod meets a divide | `docs/building-with-mycelium.md` |
| What makes a good addition; rules for documents | `docs/admission.md` |
| What a contract package is made of | `CONTRACT-PACKAGE.md` |
| One contract's meaning and laws | its `SPEC.md` in `contracts/<slug>/` |
| Worked examples of crossings | `contracts/README.md` |
| Every contract's id, path, package | `contracts/registry.json` (checked; the only such mapping) |
| Versions, eras, the pure layer's laws and gates | `docs/eras.md`, data in `eras/` |
| The plugin: options, tasks, shipping a jar | `docs/plugin.md` |
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
├── gradle-plugin/          the plugins, an included build (map below)
├── run-contracts.sh, .ps1  the harness without Gradle: identical behaviour
└── build.gradle.kts        applies both plugins to this repository; `./gradlew check` runs everything
```

### The plugin, class by class

`gradle-plugin/src/main/java/dev/woflo/mycelium/gradle/`. The repository check fails if a class is missing here. What
the tasks do is `docs/plugin.md`.

- **Wiring.** `MyceliumPlugin.java` (`dev.woflo.mycelium`, for consumers: registers the tasks, wires them into `check`
  and every jar), `MyceliumExtension.java` (the `mycelium { }` block), `PureSpec.java` (`pure { }`), `RangeSpec.java`
  (`range { }`), `LinkageSpec.java` (`range { linkage { } }`), `CapsuleSpec.java` (the capsule's ceilings, nested jars
  and mapping profile).
- **Contracts.** `ContractRegistry.java` (reads `contracts/registry.json` strictly; resolves selections and
  `supersedes`), `ImportContractsTask.java` (`myceliumImport`), `VerifyContractsTask.java` (`myceliumVerify`),
  `Harness.java` (what every runner shares: the harness's sources, and the bundle an era gate runs),
  `UsageTask.java` (`myceliumUsage`).
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
- **Shipping a jar.** `PureShipTask.java` (`myceliumPureShip`), `DowngradeJarTask.java` (`myceliumDowngradeJar`),
  `DowngradedMetadata.java` (the required annotation API closure, under the artifact's private prefix),
  `PrivateLibraries.java` (package relocation for declared feature libraries), `SurfaceTask.java` (`myceliumSurface`),
  `CapsuleTask.java` (`myceliumCapsule`), `RuntimeBootClasspath.java` (an optional buildscript helper that resolves
  ordered Java 8 bootstrap roots from a toolchain launcher).
- **Evidence.** `EvidenceTask.java` (`myceliumEvidence`), `Fingerprint.java` (stable SHA-256 over files and
  directories, for inputs and evidence).
- **This repository only.** `MyceliumRepositoryPlugin.java` (`dev.woflo.mycelium.repository`), `RepositoryCheck.java`
  (the repository against what it states), `RepositoryCheckTask.java` (`myceliumRepository`),
  `RefreshVersionsTask.java` (`myceliumRefreshVersions`), `RefreshNamesTask.java` (`myceliumRefreshNames`),
  `NameRefresh.java` (what the refresh does apart from the task).

The self-tests, `gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java`, break a scratch copy of the
repository in each way a check exists for, and prove the check names the break.

## Hard rules

1. Contract models, fixtures and the harness never touch Minecraft or a loader. If a declaration needs a Minecraft type,
   that part is a provider's work.
2. `fixtures/` and `harness/` never ship. `myceliumImport` copies `model/` only, and refuses a model that reaches the
   harness.
3. A model mentions no other contract's package, except the `model` package of one its registry row requires (exact ids,
   acyclic, none retired; decision 0014, contracts compose by declared requirement). Otherwise contracts compose in the
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


On a machine shared with other JVMs (a game server, say), keep Gradle's heap small (`GRADLE_OPTS=-Xmx512m`), run one
Gradle build at a time, and never stop a daemon that is not yours.

---

*When a contract's behaviour disagrees with its SPEC, the SPEC changes and the reason is written down: it is normative
because it is kept honest. When `DESIGN.md` doesn't say, that is a decision to make and record, not to guess past.*
