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

- **Slot registry validation at build time:** `SlotRegistry.of` validates declarations when constructed. A Gradle task
  that runs the check on a consumer's declarations is not implemented.
- **Presentation fixture automation:** `gradle-plugin/src/test/fixtures/presentation-consumer` checks task wiring and
  packaging when run separately. The plugin self-test exercises the compiler but does not build this fixture.
- **Overlays inside one jar:** `overlays { }` selects a resource directory for a target. Generation of `pack.mcmeta`
  overlays for a jar spanning resource schemas is not implemented.
- **Range evidence from test runs:** keystones are declared by the consumer. The plugin cannot infer which game tests
  ran; ranges without keystones warn and remain `interpolated`.
- **Local contract incubation:** a `localContracts` input would let a mod develop a package under the same gates before
  moving it upstream. Until then, develop packages in the separate Mycelium checkout or fork the mod builds with.
  [Decision 0015](decisions/0015-a-compiler-you-build-with.md).

## Data and verification

- **Pack formats:** rows marked `wiki` in `eras/minecraft.json` need verification against game jars. Other rows cite
  the game's `version.json`.
- **Platform ports:** era coverage is data coverage. A mod reaches another target only when its platform layer and
  full game suite run there; see [porting lessons](reference/porting-lessons.md).
- **Contract index:** `contracts/README.md` is maintained by review; the repository gate checks the registry and package
  tree, not completeness of the prose index.
- **Corpus storage:** storage beyond the file tree and registry remains an open design question from `DESIGN.md` §15.
