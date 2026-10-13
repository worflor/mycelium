# Status board

Known work, loose ends and warnings worth keeping visible. It is a board, not a roadmap: work happens without ever
appearing here, and an item leaves when it is done. What is true of the repository is checked by `./gradlew check`; the
counts are on `README.md`'s status line. History is `CHANGELOG.md`.

## In flight

- **`world.clock@1` bridge: what is not yet run or built.** `native-clock` and `emulated-clock` are built (`bridges/world-clock`) and
  linked on every version with name data; conformance has run in Somnium on 26.3 and 1.21.1 only (decision 0018, "Evidence"). Not
  built: `emulated-clock-takeover` (optional); `ClockConformance.sleeping` (a real sleeper) has no harness that makes the server
  count a mock player as sleeping. Not run in a game: 26.1, 26.2 and the pre-26.1 versions other than 1.21.1.

- **The Sigil in Somnium: adopted** (`docs/decisions/0012-sigil-item-presentation.md`, "Somnium, adopted"). Not run in a game: 26.1, 26.2 and 1.14 to 1.21.3
  other than 1.21.1 (linked, not played). The conformance classes still ship in a mod's jar unless it opts them out.

- **First consumer use of the Threshold** (the Sigil's is above). The Threshold (`state.lifecycle@1`) is a contract;
  Somnium adopts it next (its session state, scheduled work and relog handling). Its in-game evidence comes from that consumer's gametests.
- **A build-time check of a mod's slot registry.** `state.lifecycle@1` checks a registry when the mod builds it at run
  time (`SlotRegistry.of`); a Gradle task that runs the same check at build time is not written.
- **The presentation fixture in the self-tests.** `gradle-plugin/src/test/fixtures/presentation-consumer` proves the task
  wiring when built by hand; the plugin's self-test does not build it.

## Not built

- **Overlays inside one jar.** `mycelium { overlays { } }` chooses a resource directory per schema era; the game's own
  `overlays` in `pack.mcmeta` (one jar serving several pack formats, `supported_formats` ranges across a wall) is not
  generated. `myceliumPackMeta` writes one target's `pack.mcmeta`.
- **Range proofs from test runs.** The plugin cannot see which versions a mod's tests ran on, so keystones are still
  declared; a range with none warns (R1) and its versions stay `interpolated` (decision 0017, item 4).
- **Pack formats for versions that have no jar here.** The `packFormat` rows marked `wiki` in `eras/minecraft.json` were read
  from minecraft.wiki's table, not a game jar; the others match the game's own `version.json`.
- **Incubating a package in the mod that needs it.** A `localContracts` input to the plugin, so a mod can develop a
  package under the same gates in its own repository before it moves here (decision 0015). Until it exists, the package
  is written here, in the same piece of work as the mod's use of it.
- **Rungs.** Platform ports, each when a mod needs it: 1.20.1 (Fabric, Forge); 1.18.2; 1.16.5 (Java 8); 1.12.2 (Forge,
  Legacy Fabric; no data packs); 1.7.10; Beta 1.7.3. The era table already covers every one; what is missing is the
  platform layer of a mod running there (`docs/eras.md`, `docs/reference/porting-lessons.md`).

## Open questions

- Contract format and corpus storage beyond a file tree and a registry (`DESIGN.md` §15).
- Where each of the design's gates (`DESIGN.md` §14) stands against real mods. It is recorded in `CHANGELOG.md` and
  `docs/decisions/` as it moves.

## Warnings

- The contract index (`contracts/README.md`) is prose. Nothing fails when a package is missing from it.
- Comments in the plugin's source cite `docs/eras.md` sections that have since moved to `docs/plugin.md` (shipping,
  downgrading, the portability surface). The behaviour is unchanged; the pointers are slightly off.
- Several mods build on Mycelium at once, and so do several agents. A change in place to a package's meaning reaches all
  of them; make it a new major.
