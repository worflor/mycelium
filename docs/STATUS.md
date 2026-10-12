# Status board

Known work, loose ends and warnings worth keeping visible. It is a board, not a roadmap: work happens without ever
appearing here, and an item leaves when it is done. What is true of the repository is checked by `./gradlew check`; the
counts are on `README.md`'s status line. History is `CHANGELOG.md`.

## In flight


## Not built

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
