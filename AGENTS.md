# AGENTS.md: Mycelium

How to work in this repository: what Mycelium is, what you may change, how to prove a change, and what may be added.
It is written for AI agents and human contributors alike. `AGENTS.md` is the onboarding document here; there is no
`CLAUDE.md`.

## 1. What Mycelium is

**Build once, deploy everywhere.** A Minecraft mod is written once and built for every loader and version it can
reach, with evidence of what holds on each. Mycelium serves every size of mod, from a server-side integration to a
total conversion. It gives a mod three things:

- **Contracts** (`contracts/`): typed, versioned domain capabilities. Each has laws, and a rejection matrix of
  broken implementations that its harness must catch before any provider exists.
- **A Gradle plugin** (`gradle-plugin/`). It imports contract models under the mod's own namespace, gates the build on
  every harness, checks the mod's pure layer on every era's Java, keeps optional integrations isolated, and writes an
  evidence manifest into the jar.
- **A convention**, the tower (`DESIGN.md` §5). It keeps platform access in one layer, so the rest of a mod is the same
  everywhere.

It ships no runtime. Everything a mod imports becomes that mod's own private code.

| | |
|---|---|
| Java | Contracts and harness: written for 17, proven on every Java an era runs on (`docs/eras.md`). Plugin: built for 17. |
| Dependencies | None at design time. The plugin uses Gson and ASM; era gates fetch JvmDowngrader and each era's JDK when needed. |
| License | MPL-2.0 (`LICENSE`). A mod built with Mycelium keeps its own licence; files taken from Mycelium stay MPL-2.0. |
| Design | `DESIGN.md` (v0.4, frozen). Read it once, in full, before proposing an architectural change. |
| State | `README.md`'s status line (derived by the build), `CHANGELOG.md` (history), `docs/decisions/` (why). |

Several mods, by different people and agents, build on Mycelium at once. A change here reaches all of them. Keep
changes additive, version what is not, and coordinate before changing a package another consumer depends on (its
`provenance` in `contracts/registry.json` says who it was written for).

## 2. Principles

1. **Claims discipline** (`DESIGN.md` §11, §14). A passing harness is *design-time* evidence: the reference model and
   the rejection matrix hold. It says nothing about a real game. Game-target evidence belongs to the consumer, from its
   own gametests. The evidence manifest (`META-INF/mycelium/evidence.json`) records what ran and what was not checked,
   with why. It never claims in-game behaviour, and neither may any document or commit here.
2. **A no-op fails every contract.** Safety alone is satisfiable by doing nothing, so every contract has laws a
   do-nothing implementation breaks, and its matrix proves it (`DESIGN.md` §8.3).
3. **No shared runtime.** Imported models live under each consumer's own namespace, privately. Two mods pinning
   different majors never conflict (`DESIGN.md` §1, §12).
4. **Native is first-class.** A mod on Mycelium can do anything a mod without it can. Providers and native extensions
   call the platform directly, through no wrapper of ours. Mycelium makes the platform layer small, never hidden, and
   promotes a native pattern to a contract only when independent consumers show the same semantics (`DESIGN.md` §4).
5. **No premature abstraction.** Typed records, sealed outcomes, small plans. No effect language, event bus or DSL
   (`DESIGN.md` §7, §13).
6. **Everything stated is derived or checked.** A fact this repository states fails the build when it stops being
   true (`docs/decisions/0004-the-repository-checks-itself.md`).

What may be added, and what may not, is `docs/admission.md`. Read it before adding a contract, a task, an era or a
loader.

## 3. The tower (`DESIGN.md` §5)

| Layer | Where | What lives here | Touches Minecraft? |
|---|---|---|---|
| L0 Intent | the consumer | Authored behaviour, feel criteria, feature identifiers | no |
| L1 Kernels | the consumer's pure layer | Pure algorithms over explicit inputs | **never** |
| L2 Contracts | **`contracts/`** | Typed, versioned capabilities and their laws | **never** |
| L3 Seams | the consumer | Planning and composition, reaching the platform only through L2 | only through contracts |
| L4 Providers | the consumer, per loader and era | Native implementations, lifecycle binding: the platform-access layer | yes, with full power |
| L5 Machine | **`gradle-plugin/`**, plus the consumer's buildscripts | Import, verify, eras, isolation, evidence | n/a |
| L6 Verification | contract harnesses; the consumer's gametests | Rejection matrices, era gates, real-game evidence | test-time |

Mycelium proper is L2 and L5. The rest lives in each consumer.

## 4. Map

```
mycelium/
├── DESIGN.md               founding design, v0.4, frozen (archive/ holds earlier versions)
├── README.md               the front page: status line (derived), layout, tasks, using the plugin
├── AGENTS.md               this file
├── CONTRACT-PACKAGE.md     what a contract package is made of, and what keeps each rule
├── CHANGELOG.md            history: what changed, what ran, what was found
├── docs/
│   ├── admission.md        what belongs in Mycelium
│   ├── eras.md             every Minecraft version: the pure layer's laws, the era gates, the platform map
│   ├── integrations.md     optional integrations, isolated
│   └── decisions/          one decision per file, dated, with its evidence
├── eras/
│   ├── minecraft.json      every version by era: Java, loaders, names, walls
│   ├── loaders.json        each loader's packages (and the game's); the pure layer may mention none
│   └── versions.json       every real version, from Mojang's launcher manifest
├── harness/
│   ├── sources.txt         what the harness is compiled from; every runner reads it
│   ├── src/                Check, Suite, ContractRun (the rules every matrix keeps), Seeded
│   └── test/               the harness's own checks, each a main; every runner runs them first
├── contracts/
│   ├── registry.json       id, path, package, status, supersedes, provenance: the only such mapping
│   ├── patterns.json       code copied between packages on purpose, kept identical
│   └── <slug>/             one package per contract (CONTRACT-PACKAGE.md)
├── gradle-plugin/          the plugins, an included build (map below)
├── run-contracts.sh, .ps1  the harness without Gradle: identical behaviour
└── build.gradle.kts        applies both plugins to this repository; `./gradlew check` runs everything
```

### The plugin, class by class

`gradle-plugin/src/main/java/dev/woflo/mycelium/gradle/`. The repository check fails if a class is missing here.

| Class | What it is |
|---|---|
| `MyceliumPlugin.java` | `dev.woflo.mycelium`, for consumers: registers the tasks and wires them into `check` and every jar |
| `MyceliumExtension.java` | the `mycelium { }` block: contracts, namespace, target, eras, pure layer, integrations, ports, artifact Jar selection |
| `PureSpec.java` | `mycelium { pure { } }`: the artifact's pure layer and its suites |
| `ContractRegistry.java` | reads `contracts/registry.json`, strictly; resolves selections and `supersedes` |
| `ImportContractsTask.java` | `myceliumImport`: copies each selected model under the consumer's namespace |
| `VerifyContractsTask.java` | `myceliumVerify`: compiles and runs each harness; collects each harness's own report |
| `Harness.java` | what every runner shares: the harness's sources, and the bundle an era gate runs |
| `UsageTask.java` | `myceliumUsage`: reports imported contracts the artifact's sources never use |
| `IsolationTask.java` | `myceliumIsolation`: optional integrations stay in their home packages (`docs/integrations.md`) |
| `PurityTask.java` | `myceliumPurity`: the pure layer's source laws, P1 and P3 (`docs/eras.md`) |
| `PureCompileTask.java` | `myceliumPureCompile`: the pure layer compiled alone, into a bundle |
| `Eras.java` | the era table: bounds, lookup, coverage, walls, availability, names, scanner Java |
| `Versions.java` | `eras/versions.json`: every real version, the list a range is enumerated against |
| `Loaders.java` | `eras/loaders.json`: each loader's family, the platform packages P1 forbids, where it declares compatibility |
| `CompatExpr.java` | the loaders' version-range syntaxes (semver, maven, api-version): read as a predicate, written from a range |
| `CompatMetadata.java` | where a built artifact declares the Minecraft versions it accepts, read as `eras/loaders.json` describes the loader |
| `ArtifactLedger.java` | a consumer's `artifacts.json`: each artifact's loader, range, proof versions and pins |
| `RangeGate.java` | the range gate: a claimed range held to the era table, the proofs, the walls and the loader's metadata, recording proven, pending and interpolated |
| `RangeSpec.java` | `mycelium { range { ... } }`: the claim, from a ledger or written out |
| `RangeTask.java` | `myceliumRange`: runs the gate on the built artifact's resources and writes its report for the evidence manifest |
| `LinkageSpec.java` | `mycelium { range { linkage { } } }`: the archive to hold, its naming, the classes left out, the name data and its cache |
| `LinkageTask.java` | `myceliumLinkage`: reads the finished artifact's references, runs the gate on each version of the range and writes its report |
| `LinkageGate.java` | the linkage gate: every reference resolves on each version of a range that has name data, recording linked beside proven, pending and interpolated |
| `References.java` | every class, method and field an artifact's class files reach, read from the bytes, with inherited members traced to where the game would have them |
| `NameData.java` | `eras/names.json`: the pinned name data of each naming and version, strictly read |
| `NameCache.java` | the pinned files on disk by SHA-256: fetched once, checked on every use |
| `Names.java` | what the game has under one naming on one version: Searge and Tiny files as numbered members, a game or API jar through `Inventory` |
| `Inventory.java` | the game's own classes as an inventory, resolving a member through its hierarchy as the JVM does, with Mojang's mappings applied where the jar is obfuscated |
| `EraTask.java` | `myceliumEraJava<N>` (and `myceliumEras`, all of them): every bundle on an era's JVM |
| `Linkage.java` | law P2: every outside reference in a bundle exists on the era's Java |
| `CapsuleSpec.java` | the declarative scanner ceiling, nested jars, runtime roots, and mapping profile for a capsule |
| `CapsuleTask.java` | `myceliumCapsule`: bytecode ceilings and final class/member linkage for nested capsules |
| `RuntimeBootClasspath.java` | Optional buildscript helper that resolves and validates ordered Java 8 bootstrap archive roots from a selected toolchain launcher |
| `PureShipTask.java` | `myceliumPureShip`: puts the pure layer's classes (downgraded, shaded, or as compiled) into the target's jar |
| `DowngradeJarTask.java` | `myceliumDowngradeJar`: the jar's own classes downgraded to the Java the era runs on, and proven (stubs, Java linkage); a consumer's loader step takes its output |
| `DowngradedMetadata.java` | Carries only the required, target-compatible JvmDowngrader annotation API closure under the artifact's private prefix and remaps its metadata references |
| `PrivateLibraries.java` | Package relocation for declared feature libraries and their callers, before downgrade; preserves resources and refuses conflicting identities |
| `SurfaceTask.java` | `myceliumSurface`: platform references per package, outside declared ports (`docs/eras.md`) |
| `EvidenceTask.java` | `myceliumEvidence`: `META-INF/mycelium/evidence.json`, what ran and what was not checked |
| `Fingerprint.java` | stable SHA-256 over files and directories, for inputs and evidence |
| `MyceliumRepositoryPlugin.java` | `dev.woflo.mycelium.repository`, for this repository only: its `check` |
| `RepositoryCheck.java` | the repository against what it states (registry, packages, models, patterns, eras, documents) |
| `RepositoryCheckTask.java` | `myceliumRepository`: runs it, with what the harnesses ran and the harness's own checks |
| `RefreshVersionsTask.java` | `myceliumRefreshVersions`: rewrites `eras/versions.json` from the manifest, by hand |
| `RefreshNamesTask.java` | `myceliumRefreshNames`: pins name data in `eras/names.json` for given versions, or reports a pin whose file changed, by hand |
| `NameRefresh.java` | what the refresh does apart from the task: resolving a version's inputs, drift, and the lock's one written form |

The self-tests, `gradle-plugin/src/test/java/dev/woflo/mycelium/gradle/SelfTest.java`, break a scratch copy of the
repository in each way a check exists for, and prove the check names the break.

## 5. Hard rules

1. Contract models, fixtures and the harness never touch Minecraft or a loader. If a contract needs a Minecraft type,
   it is not a contract; that work is a provider's.
2. `fixtures/` and `harness/` never ship. `myceliumImport` copies `model/` only, and refuses a model that reaches the
   harness.
3. A model never mentions another contract's package. Contracts compose in the consumer
   (`docs/decisions/0005-what-a-contract-is-made-of.md`).
4. Labels are a contract's public surface. Renaming one, or changing a law, an outcome or a required method, is a new
   major (`CONTRACT-PACKAGE.md`, rule 12).
5. The plugin never gains loader-specific logic. Loader knowledge is data (`eras/`) or a consumer's buildscript.
6. `contracts/registry.json` is the only place a contract's id, path and package are declared together.
7. `DESIGN.md` is frozen. Decisions that go beyond it are recorded in `docs/decisions/`.

## 6. How to change Mycelium

Every loop ends the same way: `./gradlew check` green, and a `CHANGELOG.md` entry saying what changed and what ran.

**Add a contract.** Read `docs/admission.md`. Write `SPEC.md` first, then `model/`, then `fixtures/` (the reference and
every broken implementation, a `NoOp*` among them), then the harness (`CONTRACT-PACKAGE.md`). Add the registry row in
the same change. Iterate with `bash run-contracts.sh <slug>` until the reference is clean and every entry `CAUGHT`.
Don't wire it into a consumer's build; that is the consumer's decision.

**When a consumer finds a law the contract never stated.** Its bug may be one any implementation could make. If so,
the law belongs here: add the law, a label, and a fixture that makes the mistake, and see the harness catch it. Say in
the SPEC, in words, where it was learned. Additive changes stay in `@N`.

**Change a contract's meaning.** That is a new major: a new package directory, `supersedes` in its row. Existing
consumers keep the old one until they move.

**Change the plugin.** Keep it loader-blind. Add a self-test that breaks what the change checks. Run `./gradlew check`
here, then at least one consumer's build, which is the independent proof. A new task or option meets
`docs/admission.md` ("A plugin task or option").

**Add an era, a loader or a version.** Edit `eras/minecraft.json` or `eras/loaders.json` with sources, or run
`./gradlew myceliumRefreshVersions` when a version ships. The repository check proves the table still covers every
version exactly once. Name data for a version is pinned with `./gradlew myceliumRefreshNames --namespace=<name>
--versions=<v,...>`, and the same check holds each entry to a real version some loader runs under that naming.

**Change the harness.** It is every contract's evidence. Prove the rule in `harness/test` and run every package.

**Change a document.** Living documents stay timeless (`docs/admission.md`, "A document"). A decision gets its own
record: copy the structure of `docs/decisions/0001-substrate.md` (date, status, context, options, decision, evidence,
consequences).

## 7. What checks what

`./gradlew check` at the root runs all of it. The runner scripts are the quick path for the harnesses alone.

| Gate | What it proves |
|---|---|
| `myceliumVerify` | every harness: the reference clean, every matrix entry caught for its label, a `NoOp*` among them |
| `myceliumPurity`, `myceliumPureCompile` | the contract models are a pure layer (P1, P3), and compile alone |
| `myceliumEras` | every harness on every Java an era runs on: stubs, linkage (P2), behaviour |
| `myceliumRange` | an artifact's claimed Minecraft range against the era table, its proofs (a keystone, and a proof on each side of a wall inside the range) and the metadata the built artifact declares to its loader |
| `myceliumLinkage` | every reference the finished artifact makes resolves in the game's published names on each version of its range that has name data (`eras/names.json`) |
| `myceliumRepository` | registry against disk, package shapes, model ownership, copied patterns, era coverage, loader packages, document counts, paths and maps, and the harness's own checks |
| `selfTest` (`gradle-plugin`) | every repository check fires on the break it exists for; era, registry, evidence and integration semantics |
| `bash run-contracts.sh`, `.\run-contracts.ps1` | the harness's own checks, then every package, without Gradle |

A green gate is one input to acceptance, never the decision (`DESIGN.md` §11). Skipping a fixture to get green is
insufficient evidence, not a pass.

## 8. Scope for autonomous agents

You may, without asking:
- change `contracts/`, `harness/`, `gradle-plugin/`, `eras/`, `docs/` and the living documents, within the rules above;
- add a contract end to end, with its registry row;
- fix a plugin bug, with a self-test that proves the fix;
- run every gate and runner.

Ask first:
- before changing an existing contract's laws, outcomes or labels in place (that is a new major);
- before editing a package another consumer is developing (see its provenance);
- before adding a dependency (Gson and ASM in the plugin; nothing in contracts or the harness);
- before publishing, tagging, adding a LICENSE, or anything destructive outside this folder;
- before touching another repository. A consumer's repository is read-only from here: read it to understand a need,
  and change it only from its own repository, with its owner's go-ahead.

Rejection matrices, laws and `contracts/registry.json` are protected. Never weaken a law or delete a matrix entry to
make your own work pass: a repair must not authorize itself (`DESIGN.md` §12).

## 9. Gotchas

| Area | Issue | Mitigation |
|---|---|---|
| Claims | A passing harness is tempting to read as "works in Minecraft" | It proves the model and the matrix. Game evidence is the consumer's |
| No-ops | Safety laws alone let a do-nothing implementation pass | Every matrix has a `NoOp*` entry; `ContractRun` fails a matrix without one |
| Package names | Slugs are kebab-case; Java packages cannot be | The registry's `package` is the only mapping; never recompute it |
| Transactions | A new transaction shape invites undo-order and nested-commit bugs | Copy `FlowTransaction` and rename it; `contracts/patterns.json` keeps the copies identical |
| Coupling | Adding a Minecraft type to a contract "just this once" | It breaks purity and the no-runtime rule. The consumer's seam converts |
| Shared vocabulary | Ten `Reason` enums look like duplication | They share a name, not a meaning. Each is its own contract's (decision 0005) |
| Vacuous passes | A gate given nothing to check can look green | Every report says what it checked; evidence carries `"checked": false` with a `why` |
| Old versions | A build that compiles is not a port | A rung is ported when its whole suite runs there (`docs/eras.md`) |

## 10. Commands


On a machine shared with other JVMs (a game server, say), keep Gradle's heap small (`GRADLE_OPTS=-Xmx512m`) and run
one Gradle build at a time.

## 11. Open questions

- Contract format and corpus storage beyond a file tree and a registry (`DESIGN.md` §15).
- The design's gates (`DESIGN.md` §14) are measured against real consumers; where each stands is recorded in
  `CHANGELOG.md` and `docs/decisions/` as it moves.

---

*When a contract's behaviour disagrees with its SPEC, the SPEC changes and the reason is written down: it is normative
because it is kept honest. When `DESIGN.md` doesn't say, that is a decision to make and record, not to guess past.*
