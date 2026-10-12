# Changelog

## 2026-10-12 - item.presentation@1: the Sigil, item presentation compiled from component state

New contract `item.presentation@1` (`contracts/item-presentation`, `docs/decisions/0012-sigil-item-presentation.md`), written for Somnium,
requiring `item.component@1`. An item's look is declared once as a program: inputs over component fields with finite domains
(flag, present, int range, enum), ordered cases with conjunctive guards, model-reference templates and a base. The compiler partially evaluates
it into selections, takes their legacy codes from the consumer's committed append-only lock, and emits the target era's artefact (an item
definition selecting on the custom model data strings, or an item model with ascending overrides) beside the plan the runtime adapter
evaluates, in canonical JSON. Laws S1 to S7 (totality, agreement, stable codes, override order, projection, determinism, bounded); S2 is checked by
simulating each era's rules over the emitted bytes, for every tuple.

- **Plugin.** `myceliumPresentation` compiles `src/main/presentation` for the target's realization into generated resources, wired before
  `processResources`, and fails the build on any breach with the diagnostic and, for a missing selection, the lock lines to add;
  `myceliumPresentationLock` appends them to the committed lock on request. `mycelium { presentation { } }` places the sources.
- **Era data.** `eras/minecraft.json` gains a `presentation` table (item-definition from 1.21.4, model-overrides 1.14 to 1.21.3, none before),
  its own table because the ranges cross the era rows; `myceliumRepository` proves every real version falls in exactly one range.
- **Verification.** Six broken compilers join the matrix (`EnumerationOrderCodes`, `DescendingOverrides`, `OffByOneLegacy`,
  `LeakyProjection`, `HashOrderEmitter`, `NoOpCompiler`), each caught for its label; 40 seeded random programs are held to S1, S2, S4 and S6.
- Where the decision was silent it was decided and recorded in it: input defaults, out-of-domain stacks drawing the base, retired codes drawing
  the base, a catalog file held to the registry, and the base model being another model than the item's own.

Ran: `bash run-contracts.sh item-presentation` (clean, 6 rejections caught), `./gradlew -p gradle-plugin selfTest`, the
`presentation-consumer` fixture (`check` on 1.21.4; the lock task and the build fail and recover as described), `./gradlew check`.


## 2026-10-12 - Contracts compose by declared requirement

A registry row may carry `requires`: exact ids of the contracts whose `model` packages its model may import
(`docs/decisions/0014-contracts-compose-by-declared-requirement.md`, amending the rule of decision 0005 that a model mentions no
other package). `ContractRegistry` refuses a requirement that does not exist, is retired, is the contract itself, or closes a
cycle; `myceliumRepository` fails a model that reaches an undeclared package or a required one's fixtures or harness;
`myceliumImport` imports a contract's requirements with it under the consumer's namespace (so the importing model refers to the
consumer's private copy), `myceliumUsage` does not call a contract imported only as a requirement unused, `myceliumVerify` and
both runner scripts compile a package with its requirements' models (`harness/tools/RequiredModels.java` reads the registry for
the scripts), and the evidence manifest lists each contract's `requires`. `AGENTS.md` hard rule 3 and `CONTRACT-PACKAGE.md`
rule 10 state the new rule. Additive: no existing row declares a requirement.

Ran: `./gradlew -p gradle-plugin selfTest` (a requirement chain, an unknown, a retired, a self and a cyclic requirement are
refused; an undeclared, a fixtures and a harness import fail the repository check; a declared one passes; importing a contract
with a requirement yields both, namespaced, with no fixtures).

## 2026-10-12 - state.lifecycle@1: the Threshold

Added `state.lifecycle@1` (`contracts/state-lifecycle`, decision 0013): state declares how it crosses. Slots (session or
durable, scoped to a host kind) declare a policy for every transition (keep, drop, reset, migrate, and at a fork copy,
regenerate or exclusive); hosts are named by id and incarnation, so a `HostRef` held by a delayed task resolves to nothing
once the host has logged out, respawned or reloaded; a pure engine turns a transition into an ordered plan that applies
inside a `Tx` with undo. Laws L1 to L8; a declaration check that names the slot and the transition of every problem.
Written for Somnium, whose static session maps, player-keyed holds and closures over player objects it replaces.

- **Verification:** reference clean; the matrix of nine broken implementations (`ForgetfulEngine`, `IdOnlyResolver`,
  `CloningExclusive`, `MaterializingPeek`, `DefaultPolicy`, `HalfCrossing`, `ForgetfulStorage`, `RotatingEngine`,
  `NoOpEngine`) all caught, each for its own law. L2, L3, L4 and L8 hold after every step of seeded random sequences.
- **Registry:** rows may carry `requires` (exact ids), and a model may import the `model` package of a contract it requires and
  nothing else of it; the repository check, `myceliumVerify` and both runner scripts honour it. This is the minimal form
  needed here; the item-presentation work defines the full mechanism, and this commit is replaced by it at merge.
- `persistence.player-state@1` is unchanged; its SPEC now says how `RESPAWN_SAFE` relates to the lifecycle policies.

Ran: `bash run-contracts.sh state-lifecycle`, `./gradlew check`.

## 2026-10-12 - item.component@1: a lens with nothing left over retains nothing

Reading a component whose definition has a `FieldAwareCodec` recorded its remainder even when it was empty, so a value read
and written back was a `CarrierSnapshot` unequal to the one it came from (T1). `StandardRosettaTranslator` now retains a
remainder only when it holds something, and the record laws assert whole-snapshot equality. Found by Somnium's first
record components; additive within `@1`.

## 2026-10-11 - item.component@1: Data Component <-> NBT Carrier Translation

Added `item.component@1` (`contracts/item-component`): a pure, deterministic bidirectional translation and carrier model
bridging modern Minecraft Data Components (1.20.5+) and legacy item NBT compounds (pre-1.20.5, specifically 1.20.1), written
for consumers with immutable component definitions.

- **Pure Model:** `TagNode` algebraic data type representing NBT AST without Minecraft dependencies, `ComponentCodec<T>`,
  `ComponentDefinition<T>`, `ComponentRegistry`, and immutable `CarrierSnapshot`.
- **Laws Enforced:** T1 Round-Trip Isomorphism, T2 Default Elision (unmutated items emit zero tags in 1.20.1), T3 Fenced
  Namespace (`mycelium$c`, zero root pollution), T4 Foreign Tag Preservation, T5 Diagnostic Rejection on Corruption, and
  T6 Lexicographical Key Ordering.
- **Verification:** Rejection matrix catches 6 broken implementations (`NoOpTranslator`, `DefaultWritingTranslator`,
  `RootPollutingTranslator`, `LossyUnknownTagTranslator`, `CrashingParseTranslator`, and `UnsortedKeysTranslator`).
- **Hardening & Java 8 Linkage:** Explicit integer-cast `toString()` on `TagByte` and `TagShort`, `Arrays.toString()` on
  array tags, strict homogeneity validation on `TagList`, lenient numeric coercion on `BOOLEAN`, `listOf` combinator,
  and replacement of post-Java-8 methods (`Optional.isEmpty()`, `String.isBlank()`) ensuring clean bytecode lowering
  and linkage resolution across all historical eras down to Java 8.

Ran: `.\run-contracts.ps1 item-component` (clean, 6 rejections caught), `.\run-contracts.ps1` (all 19 packages PASS,
142 rejections caught), and `./gradlew check` (all 18 tasks green: `myceliumRepository`, `myceliumPurity`,
`myceliumVerify`, and era gates from Java 8 to Java 25).
## 2026-10-08 - Dedicated-server linkage surface

The optional `linkage.serverRuntimeArchive` input lets a target provide its exact dedicated-server game jar in the artifact's runtime
names. The linkage gate checks references against that class inventory as well as published names when available, or against the
inventory alone when a version has no name row. The task records its version, namespace and SHA-256; class scope is explicit and
must agree with published name data when present. This catches a mapping-listed client-only member without starting a server.

The `linkage-consumer` fixture includes a positive exact-server inventory, `-PlinkageServerBreak=1.21.1` (which leaves the method
in Tiny mappings but removes it from the server surface), and `-PlinkageServerRuntime=false` (which leaves the optional input unset).
`LinkageSelfTest` covers an inherited server method, that
mapping-present/server-absent failure, an unknown hierarchy reported as unverified, and a runtime-only version with no name row.
Verification: `./gradlew check` passes on Java 21 with the installed Java 8, 16, 17 and 25 toolchains; the positive and optional-unset
fixtures pass, and the server-surface rejection fixture fails on the expected mapping-present/server-absent method. The launcher
self-test selects the platform's Java executable, and `gradlew` is executable in source control.

## 2026-10-07 - License

Mycelium is licensed under the Mozilla Public License 2.0 (`LICENSE`). A mod built with it keeps its own licence;
Mycelium's own files stay MPL-2.0.

## 2026-10-06 - Linkage at each version

`range { linkage { } }` adds `myceliumLinkage`: every class, method and field the finished artifact references must resolve
on each version of its claimed range that has name data, or the build fails naming the reference, the class that uses it and
the version. A version whose references all resolve is recorded `linked` (the range gate's `interpolated` that was checked
against that release's published names), a `proven` or `pending` version keeps its status and is linked as well, and a version
with no name data stays `interpolated` with the reason. The report is `build/mycelium/linkage/linkage.json`, which names the
archive it read by SHA-256; the evidence manifest's range report says linkage is deferred to it.

The name data is a lock, `eras/names.json`: one entry per naming and Minecraft version, each input pinned by SHA-256 at the
URL it is published at (Searge's `joined.srg`, Fabric's intermediary, Mojang's server bundle and mappings, the game's own
unobfuscated jar, Spigot's API jars). Pins are fetched once into a cache keyed by hash and checked on every use, so a build
that has run once links offline; `myceliumRefreshNames` pins more versions or reports a pin whose file has changed. The
repository check holds every entry to the version list and the era table. Readers: `srg-flat` and `tiny-flat` hold classes
and numbered members, `jar` and `proguard-jar` hold every member through its hierarchy. `namespace` states the naming where the
era table's is not the artifact's (a plugin: `api`), `exclude(...)` leaves out classes an artifact chooses between at runtime by
probe, and a capsule's own gate links its version against the final runtime view without name data
(`docs/decisions/0010-linkage-at-each-version.md`).

Verification: self-tests (`linkage:`) for the reference scan, each reader, the inventory's resolution through hierarchies, the
lock's refusals, the cache, the refresh task and its lock form, and the gate: a planted reference that does not exist on one
version of a range, a changed descriptor, a missing field on a proven version, an inherited method lost, a capsule's version,
an unavailable input online and offline, and exclusions, each refused or let stand for its reason; repository breaks for name
data of a version the launcher does not name, data no loader runs under, a pin that is no SHA-256 and a missing lock. The
`linkage-consumer` fixture builds a Fabric artifact whose range's first version is linked, and `-PlinkageBreak=1.21` removes the
method it calls from that version's data, which the build refuses by name.

## 2026-10-06 - myceliumDowngradeJar for a Java between 9 and 17

The shade step of `myceliumDowngradeJar` is told the target's class version (`-c 60` for Java 16), as the downgrade step
already was. Left at the tool's default it wrote its API archive downgraded to class version 52, and the task, which reads
the archive named for the target (`-downgraded-60`), found none: a jar for a game on Java 16 (Minecraft 1.17.x) could not
be downgraded, where Java 8 (52) and Java 17 and later (no downgrade) could. On Recon's 1.17.1 jar the shaded result is
the same size with either class version. Self-test: `downgrade:` holds the shade command to carry the class version of Java 8 and Java 16.

## 2026-10-06 - A plugin's api-version across the flattening

A server before 1.13 reads no `api-version`, so the key is a floor only where it is read. `CompatExpr.parse("api-version", v)`
accepts every version before 1.13 as well as every version from `v` on, and `render` writes a range that reaches 1.13 or
later as `1.13` at the earliest (a range that ends before 1.13 is still written as no version). A plugin that declares
`1.13` holds a claimed range from 1.8.8 to 26.x: a server from 1.13 on runs it as a modern plugin, with no legacy
material support, and an older one ignores the key. A plugin that declares none is still refused wherever the range reaches
1.13 or later, and one that declares a version newer than the range's start is still refused where it would not load.
Self-tests: `compat:` for the predicate and the rendering.

## 2026-10-06 - myceliumRange runs after compileJava

`myceliumRange` depends on `compileJava` as well as `processResources`. ForgeGradle (Forge 1.21 and later) writes classes
and processed resources into one directory, and Gradle refuses a task that reads a directory another task writes without
a declared order. The `range-consumer` fixture takes `-PsharedOutput` to lay its output out that way; the build is refused
without the order and passes with it.

## 2026-10-06 - chat.invocation@1: a gate that has been told nothing, and settings that change it

`Gate.settings(Rules)` (a `default` method that ignores its argument) and `new ChatGate()` (the default rules) are added,
with laws `P6` and `P7`. P6: a gate that has been told nothing, because the assistant is absent, has crashed or has not
yet connected, judges by the default rules from its very first line: ordinary chat is `Passed` at once, and an addressed
line is committed even as the first line; nothing is held, cancelled or delayed while the settings are unknown. P7:
settings apply to the next line (the trigger, the longest query, the snapshot's size and the bucket), and a settings
change never brings back into a snapshot someone who opted out. The harness takes a second factory for the gate that has
been told nothing (with one factory it is a gate built with the default rules). Additive in `@1`, by the precedent of
`readyInMs` and `trigger`: a gate that predates `settings` still compiles, keeps every earlier law and fails
`P7:settings-apply-at-once`. `Run.java` gains `HoldingGate` (P6), `DeafToSettingsGate` and `ForgetfulGate` (P7).
Matrix size 17.

Verification: the chat.invocation harness passes (reference and an independent second gate clean, all 17 rejection
entries caught, each by its own label).

## 2026-10-06 - The range gate

`mycelium { range { ... } }` declares the Minecraft versions an artifact says it runs on (`from` to `to`, inclusive, the
last possibly a line) and what proves it: keystones (versions servers really run) and flex (one on each side of a wall),
read from a consumer's ledger (`ArtifactLedger`, `artifacts.json`) or written out. `myceliumRange` fails the build when
a wall inside the range has no proof on each side, a proof is outside the range, a version in the range is one no claimed
platform exists on, or the metadata the built artifact declares to its loader refuses a version it claims. The
metadata can be written from the range (`mycelium.compat()`), so it is stated once. The evidence manifest is schema 3
and records each version as `proven`, `pending` or `interpolated`, with the metadata check (or why it was not made) and
that no linkage was run at the proof versions (`docs/decisions/0009-the-range-gate.md`, which says what that needs).

Verification: a rejection fixture in the self-test for each way a claim fails, each refused for its reason, beside the claims the
table lets stand (hotfix pairs, a range that begins at a wall, NeoForge on its first version, a plugin across the Bukkit
family, an older Forge whose range lives in code) and the metadata readers over each loader's own format. The
`range-consumer` fixture runs the task in a build of its own: metadata written from the range, the claim in the evidence
manifest, and a planted wall break and a planted metadata break each refused by name.

## 2026-10-06 - The era table says where a loader exists, how the game is named and where a port breaks

`eras/minecraft.json` and `eras/loaders.json` are schema 2.

- Plugin platforms are loaders: `bukkit`, `spigot`, `paper`, `purpur` and `folia`, each with the version its builds
  begin on (`since`), and Quilt on 1.14.4 and later, NeoForge on 1.20.1. Every loader has a `family` (`mod`, `plugin`,
  `agent`).
- `names` is a pair: `dev` (what the source is written in) and `runtime` (what each loader runs, steps where it changes
  inside an era, as Forge's before and after SRG at 1.5.1).
- `outerJava` records the Java a loader's scanner reads, as steps (Forge: 6, then 7 from 1.6.1, then 8 from 1.7.10).
- `walls` are rows with `at`, `for` (loaders or families) and `what`, stated at the versions the old table
  only mentioned in prose and at 1.9, 1.11, 1.12, 1.16.2, 1.18.2, 1.19.1, 1.19.3, 1.20.3, 1.21.5, 1.21.6, 1.21.9
  and 1.21.11. An era boundary is not a wall.
- `loaders.json` says where a built artifact declares the Minecraft versions it accepts (`compat`), and `CompatExpr`
  reads and writes the three syntaxes (semver, maven, api-version).
- The repository check proves every wall is at a real version inside its era, every `for` names a loader or family,
  every name is in the vocabulary, and every `since` and step begins at a real version inside the era; each was shown
  to fire on a planted break. The evidence manifest's `era.names` is the pair resolved for the target, and its
  `era.loaders` are the loaders the target version offers.

## 2026-10-06 - chat.invocation@1: the trigger is named

`Invocation` and `Outcome.Rejected` carry the trigger the line began with, spelled as the rules spell it, and
`Triggers.match` answers the addressing question with it. The earlier constructors remain and leave it `null`, so every
existing implementer still compiles and keeps every existing law; a gate that does not name it fails the new law `P5`.
`Run.java` gains the matrix entry `NamelessGate`, caught by P5. Matrix size 14.

Verification: the chat.invocation harness passes (reference clean, all 14 rejection entries caught).

## 2026-10-05 - Chat coverage retired

`chat.coverage@1` has registry status `retired`, and its SPEC says so. Recon, the only consumer, redacts the logs it
reads instead of withholding them until a coverage witness proves every public line was recorded, so nothing carries the
contract. The package, its harness and its rejection matrix are unchanged and still run, so an older build keeps
resolving it. The registry's note defines the new status value.

Verification: the complete repository check passes all 18 packages, 132 rejection-matrix entries, 274 plugin assertions
and the Java 8, 16, 17, 21 and 25 era gates, with `chat.coverage@1` still resolving and passing under its `retired` status.

## 2026-10-04 - Bounded agent-link admission

`agent.link@1` supplies an additive request admission budget. One lease reserves a retained job and its encoded UTF-8
frame bytes atomically; invalid costs and full budgets refuse without consuming capacity. Closing a lease releases
the reservation once. Consumers retain the lease while work is queued or running and refuse overload before entering
the link session, preserving an unclaimed mutating id for a later retry.

Verification: the complete repository check passes all 18 contracts, 132 rejection fixtures, 274 plugin assertions
and Java 8, 16, 17, 21 and 25 era gates. Six new fixtures cover no-op admission, job and byte overbooking, rejected-cost
leaks, unreleased leases and concurrent overbooking. Consumer queues and live transports require their own evidence.

## 2026-10-04 - Agent-link refusal replay provenance

`agent.link@1` records whether a refusal came from the remembered ledger. A recoverable mutating failure is first returned
with `replayed = false`; retries after reconnect or handover return the cached `FAILED` refusal with `replayed = true`
without invoking the handler again. Fresh refusals and live `BUSY` refusals remain unmarked. The three-argument
`Refused` constructor remains available and defaults to `false`.

Verification: the direct agent-link harness passes 27 reference assertions and catches all 20 rejection entries,
including a fixture that remembers the failure but omits replay provenance. The complete repository check passes
all 18 contracts and 126 rejection fixtures, 274 plugin assertions and Java 8, 16, 17, 21 and 25 era gates.

## 2026-10-04 - Chat coverage ledger

Added `chat.coverage@1`, an immutable sequence ledger for explicitly established public-event runs. Durable commits
must be contiguous; open/write/flush/cap/fallback loss is sticky through later commits and restores, and late ownership
starts incomplete. Its design-time harness passes the positive empty-run/commit reference and catches all eight named
rejection fixtures. This models the coverage claim only; it does not prove source delivery or crash durability.

Verification: `run-contracts.ps1 chat-coverage` passes both shared harness checks, the reference, and all eight
rejection entries. The complete `./gradlew check` passes all 18 contracts, 125 rejection fixtures, 274 plugin
self-test assertions, repository checks and era gates on Java 8, 16, 17, 21 and 25. Target-provider evidence belongs
to consumers and is not claimed by this contract run.

## 2026-10-03 - Handler error replay

Mutating handler failures retain their request identity across reconnects and payload replacement. Recoverable
errors receive a remembered diagnostic; fatal VM errors retain a no-retry claim. Reply storage precedes claim
release, and rejection fixtures exercise both error replay and shared-ledger handover.

## 2026-10-03 - Capsule runtime linkage

Added the opt-in `myceliumCapsule` gate for loader-scanned outer jars and nested payloads. It enforces declared Java
ceilings, checks every bytecode class and member reference against ordered runtime roots, verifies declared Tiny v2
namespace obligations, and records the checked artifact, mapping and runtime-root hashes. It also checks inherited
members, staticness, constructors, access, nested-loader boundaries, and shadowed runtime definitions. `SelfTest`
includes positive and negative fixtures for each behavior, including array-owner `clone` and inherited `Object` calls,
missing array methods/components, and invalid static/interface invocation forms.

Verification: plugin self-tests pass 274 assertions; `./gradlew check` passes for all 17 contract/package gates, 115
rejection fixtures, repository checks, and Java 8, 16, 17, 21 and 25 era gates. The independent 1.2.5, 1.4.7, 1.5.2
and 1.6.4 Forge capsule checks pass with exact runtime inventories of 862, 4,783, 10,240 and 19,199 classes
respectively; their capsule scans report 1,824/254,958/87,618, 1,828/255,380/87,737, 1,828/255,386/87,735, and
1,827/255,381/87,735 class/class-reference/member-reference counts. These build gates do not replace real-server
runtime evidence.

## 2026-10-02 - Handler failure diagnostics

`agent.link@1` adds `L9:failure-has-diagnostic`: handler failures preserve nonblank exception messages and identify the
exception class when the message is missing or blank. Remembered refusals retain that detail across reconnects.
The rejection matrix includes `OpaqueFailureLink`; the harness checks null, empty, whitespace and ordinary messages.
The existing laws, refusal types and request replay semantics are unchanged.

Verification: the complete repository check passes with 115 rejection fixtures across 17 contracts. The agent-link
reference and its 17 rejection entries pass on Java 8, 16, 17, 21 and 25; plugin self-tests pass 208 assertions.


