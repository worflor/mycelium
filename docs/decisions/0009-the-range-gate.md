# 0009 — The range gate

| | |
|---|---|
| Date | 2026-10-06 |
| Status | Accepted (the self-tests pass); per-version linkage is `docs/decisions/0010-linkage-at-each-version.md` |
| Extends | `docs/decisions/0008-capsule-runtime-linkage.md` |

## Context

An artifact says which Minecraft versions it runs on in several places a person keeps in step by hand: the loader's
own metadata (a Fabric `depends.minecraft`, a Forge `versionRange`, a plugin's `api-version`), the table that decides
which jar a server installs, the list of servers a ladder boots, and the prose. Each can claim more than the proof
behind it, and nothing connected them to what the game changes between versions. The era table (`eras/minecraft.json`)
knows where a port breaks (its walls) and where a loader exists (`since`), but no check held a claim to it.

A version a server really runs is cheap to prove and a version a server never boots is not; most of a range is the
second kind. The honest claim is a range with a few proofs and a record that says which versions each proof stands for.

## Decision

An artifact declares a **range** (`from` to `to`, inclusive; `to` may be a line, `26.3.x`) and **proofs**: keystones, the
versions servers really run, and flex, one version on each side of a wall. A consumer keeps them, with the loader and
the pins of each proof version, in one ledger (`artifacts.json`, `ArtifactLedger`) and reads everything else from it:
the metadata the loader enforces is written from the range (`mycelium.compat()`, `CompatExpr.render`), so the claim is
stated once.

`myceliumRange` (`RangeGate`) holds the claim to what is known, and fails the build when any of it does not hold:

- both ends are versions the launcher names (`eras/versions.json`), the range runs forwards, and on every version in it
  some platform the artifact claims exists (`since`, or the era listing the loader);
- every proof is a real version inside the range, on a platform that exists there and the artifact claims; there is a
  keystone;
- every wall inside the range that splits the artifact's loader or family (`for`) cuts the range into segments, and each
  segment holds a proof: a wall in a range needs a proof on each side. A wall at the range's first version cuts nothing;
- the metadata the built artifact declares to its loader (`CompatMetadata`, read from the processed resources as
  `eras/loaders.json` describes the loader) accepts every version in the range. A plugin with no `api-version` is a
  legacy plugin and is refused where the range includes 1.13 or later; a server before 1.13 reads no `api-version`, so
  a plugin that declares `1.13` holds a range that begins earlier. Metadata that accepts versions beyond the range
  (a plugin's `api-version` is a floor) is recorded, not refused;
- pins name proof versions, and a version marked `pending` is a proof.

Where a loader declares compatibility in code the build does not read (Forge before 1.13, in an `@Mod` annotation), the
table says so (`compat.before`) and the report records the metadata as unchecked, with that reason, rather than passing it.

The report (`build/mycelium/range/range.json`) goes into the evidence manifest (schema 3) and records every version in
the range as:

- `proven`: a proof version the ledger does not list as pending;
- `pending`: a proof declared and not yet run;
- `interpolated`: inside the range with no proof of its own. This is a claim, resting on the walls the table states being
  complete and on the artifact's source being the same across the segment, and nothing was booted or linked there.

`proven` is the ledger's statement that a server ran there. The build does not run a server: what ran is the consumer's
ladder, which is evidence of its own, and the report says what the build did and did not look at.

The era table gained what the gate needs (docs/eras.md): plugin platforms, Quilt and NeoForge where they exist, `since`,
`names` as a pair (`dev`, `runtime`), `outerJava` (the Java a loader's scanner reads) and walls as rows. Walls are
explicit: an era boundary is not a wall.

## Linkage at each version

ADR 0008's `Linkage` proves every class and member a bundle references exists in a runtime view, for one view. The same
check against each version of a range needs that version's names, which a build resolves for its own node only. ADR 0010
pins them (`eras/names.json`) and adds `myceliumLinkage`, which marks an interpolated version `linked` when every reference
the finished artifact makes resolves there. A build that does not declare it records `linkage: {"checked": false, "why": ...}`
in the range report, and an interpolated version is then a claim a user's report can disprove. Narrowing the range is the
fix when one does.

## Consequences

- Widening an artifact is a row in the ledger, and the gate says what it needs: a proof on the far side of a wall, a
  loader that exists there, metadata that accepts it.
- A range cannot be larger than its metadata, and its metadata is never written by hand.
- A wrong or missing wall in the table makes the gate pass what it should not. Walls are facts about the game and are
  re-verified when a consumer first ports to a row (`docs/eras.md`).
- Interpolation is stated and counted, never hidden: a reader of the evidence can show how much of a
  claim was run.
