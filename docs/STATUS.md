# Open work

Implementation gaps and evidence still needed. Completed work belongs in [CHANGELOG.md](../CHANGELOG.md); design
rationale belongs in [decisions](decisions/). This board does not define the full scope of the project.

## Bridges and game evidence

- **World clocks:** `native-clock` and `emulated-clock` have linkage coverage where name data is pinned. Recorded
  consumer conformance covers 26.3 and 1.21.1; other versions still need game runs. `ClockConformance.sleeping` needs a
  harness whose player the server counts as sleeping. The optional `emulated-clock-takeover` remains unbuilt.
  [Decision 0018](decisions/0018-world-clock.md) records the limits and prerequisites.
- **Item presentation:** recorded consumer game runs cover 26.3 and 1.21.1. Other versions covered by the bridge still
  need game evidence. [Decision 0012](decisions/0012-sigil-item-presentation.md).
- **Lifecycle:** the bridge covers player and server hosts on the ranges in `eras/minecraft.json`; entity and level
  hosts and the intervening version ranges need implementations. Loader API references are compiled by consumers,
  outside Mycelium's game-only linkage gate. Conformance drives player removal and the bridge's stop method; a real
  client disconnect and server shutdown still need evidence. [Decision 0013](decisions/0013-threshold-state-lifecycle.md).

## Build tooling

- **External downgrader concurrency:** JvmDowngrader 2.0.1 can fail intermittently with a `HashMap$Node` / `TreeNode`
  `ClassCastException` in `PathDowngrader` parallel work. Observed again on the Java 16 `random.seeded@1` gate on
  2026-10-19. The existing single retry retains the first failure log, and successful output still needs linkage and
  suite checks. Replace that mitigation with a verified upstream fix or deterministic execution; retry success does
  not repair the underlying race.
- **Slot registry validation at build time:** `SlotRegistry.of` validates declarations when constructed. A Gradle task
  that runs the check on a consumer's declarations is not implemented.
- **Presentation fixture automation:** `gradle-plugin/src/test/fixtures/presentation-consumer` checks task wiring and
  packaging when run separately. The plugin self-test exercises the compiler but does not build this fixture.
- **Overlays inside one jar:** `overlays { }` selects a resource directory for a target. Generation of `pack.mcmeta`
  overlays for a jar spanning resource schemas is not implemented.
- **Range evidence from test runs:** keystones are declared by the consumer. The plugin cannot infer which game tests
  ran; ranges without keystones warn and remain `interpolated`.
- **Behavioural analysis:** the experimental linter diagnoses callback initialization dependencies. Unknown callback
  effects, captured instance aliases, reflective entrypoints and external scheduling still need analysis
  or runtime evidence. Implicit initializer effects need a may/must initialization-state model: a class initializer
  that clears another class's service can currently escape diagnosis. Reentrant initialization also needs that model;
  inherited member resolution alone does not establish initializer completion. Exceptional effects now have separate
  outcome summaries and typed handler routing. Complete fault preconditions, external field effects, exception-argument
  specialization and target-specific platform exception ancestry remain open; host platform headers are not a complete
  target JVM model.
  The must-binding lattice loses
  correlations between factory returns and later branches; executable controls retain that precision limit.
  Large content-mod inputs need continued scaling validation. Investigate convergence through worklist telemetry
  and constrain callback surfaces with available type facts; keep the stress inputs in the corpus. Extend rules with
  executable counterexamples and safe controls.
  Compact context-node tables would make large graph exports easier to query without repeating long argument labels.
  See the [inspection checks](plugin.md#inspecting-an-existing-mod).
  [Research priors and experiments](reference/behavioral-analysis-research.md) connect the next semantic steps to
  established analyzers and executable controls.

## Data and verification

- **Pack formats:** rows marked `wiki` in `eras/minecraft.json` need verification against game jars. Other rows cite
  the game's `version.json`.
- **Platform ports:** era coverage is data coverage. A mod reaches another target only when its platform layer and
  full game suite run there; see [porting lessons](reference/porting-lessons.md).
- **Contract index:** `contracts/README.md` is maintained by review; the repository gate checks the registry and package
  tree, not completeness of the prose index.
- **Corpus storage:** storage beyond the file tree and registry remains an open design question from `DESIGN.md` §15.
