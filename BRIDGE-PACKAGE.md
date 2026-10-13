# Bridge packages

A bridge is the native half of a divide a contract crosses: the code that touches Minecraft so a mod does not. A contract
states what must be true (`CONTRACT-PACKAGE.md`); its port, an interface in the contract's model, is what a bridge
implements. The bridge has no runtime of its own. The plugin selects the variant that covers the target version and
imports its source privately under the mod's namespace, as it does a contract's model (`docs/plugin.md`,
`docs/decisions/0016-bridges.md`).

```
bridges/<slug>/
├── bridge.json                 id, contract, port, implementation, package, divide, writtenIn, variants and their overlays
├── <variant>/
│   ├── src/<package path>/     the variant's source (the bridge's package, dots as slashes)
│   ├── conformance/<...>/      this variant's rig of the real game (how to build, copy, save a stack here); names no class of the bridge's own package
│   ├── conformance-bound/<...>/ what needs the bridge's own source (a subclass of the cases that builds the bridge); an override does not import it
│   ├── conformance-resources/  data files the cases need in the mod's resources (a clock is a datapack file on 26.1 and later)
│   └── overlays/<name>/        files that replace or add same-named files, for a range inside the variant
│       ├── src/
│       ├── conformance/
│       ├── conformance-bound/
│       └── conformance-resources/
└── conformance/<...>/          the cases, written against the port: plain Java a mod registers as game tests
```

## Rules

1. **One divide, one table.** `bridge.json` names the realization table (`divide`, e.g. `item.carrier`) in
   `eras/minecraft.json`. Each variant is a realization named in that table; `none` rows say a version has no native half.
   The repository check proves rows cover every real version exactly once.
2. **A variant is a realization; an overlay is an API shift inside one.** An overlay has a name and a range
   (`from`, `to`), lies inside its variant's range, and may not overlap another overlay. Its files replace or add files of
   the variant by path.
3. **Mojang names.** Variants are written in the namespaces `writtenIn` lists. Each variant, with the overlays covering a group of
   versions, is compiled against the newest version of the group that has name data, and its references are checked on every other
   version with data. A version without name data is reported, not linked, and `myceliumBridgeLinkage` says so in numbers ("linked N of M
   version(s), K not linked", with why); a missing toolchain or game archive is a note, never a pass.
4. **Implement the port.** The implementation class implements the contract's port; the contract's laws are held to it.
5. **Conformance on the real game, kept by the mod.** The cases are written against the port, so they hold any implementation, an
   override included (`bridges { override(...); conformance() }` gives the override the cases and the rig, not the bridge's source).
   Mycelium proves only that they compile (linkage, with the override's view); it never runs them. The mod runs them as game tests on every
   target it builds. The cases prove the laws the pure harness cannot (the stack laws, a copy, a save and
   reload, a client's copy; a clock advancing with its rate against the game's own). They are plain Java: `cases(...)` returns
   named bodies over a `Level` (and, where a law needs the server to run, `timed(...)` returns a start, a number of ticks and an end);
   the mod registers them. A case that needs data in the game (a datapack clock) has it in `conformance-resources/`, which
   `bridges { conformance() }` adds to the mod's resources beside the cases. They are test data: the mod's jar leaves them out unless
   it says `shipConformanceResources()`, and leaves the conformance classes out only if it says `conformanceClassesOutOfJar()`.
6. **Bring the contract.** A contract's `contracts/registry.json` row lists its bridges. A mod that references a bridge
   gets its contract and the contract's requirements.

## Write a bridge

1. **Name the divide.** Add a `bridges` field to the contract's row in `contracts/registry.json` (`"bridges": ["<slug>"]`) and put the
   port, an interface, in the contract's model (additive in `@N`). The contract's laws stay about the port.
2. **Add the realization rows.** In `eras/minecraft.json` add a table named after the divide (`item.carrier`), one row per range
   with `from`, `to`, `realization` (the variant's directory name) and `what`; `none` where there is no native half. Rows cover every
   version exactly once (`docs/eras.md`; `myceliumRepository` proves it).
3. **Write `bridge.json`** and each variant's `src/<package path>/`: the implementation implements the port. Where the API shifts inside a
   variant, add an overlay with a name and a range; its files replace or add same-named files.
4. **Pin the names.** `./gradlew myceliumRefreshNames --namespace=mojang --versions=<v>` for the versions the variant covers
   (`docs/eras.md`); linkage checks only versions with data.
5. **Follow the conventions.** Source that touches the game is behind two small classes: `Ports` (what the model needs of the game, in
   the variant) and `Wire` (a conformance rig: how to build, copy, save, send and read raw state on this version). Neither names the other
   half's classes; a rig that names a class of the bridge's own package is a finding.
6. **Write the conformance against the port.** The cases go in `conformance/` (plain Java, over the port and `Wire`), the rig in each
   variant's `conformance/`, and anything that must build the bridge (a subclass of the cases, as `ClockConformance extends ClockCases`)
   in `conformance-bound/`. A mod's override then gets the cases and the rig and never the bridge.
7. **Run it.** `./gradlew myceliumRepository myceliumBridgeLinkage` here, then a consumer that references
   `<namespace>.bridges.<slug>` (or `bridges { use("<slug>") }`) and, for the cases, `bridges { conformance() }`.

## What keeps each rule

| Rule | Kept by |
|---|---|
| 1, 2, 4 | `myceliumRepository` (`BridgeCheck`): rows, overlay ranges, port and implementation named |
| 3 | `myceliumBridgeLinkage`: each variant, with each overlay, compiled and linked against every version with name data |
| 4 (an override) | `myceliumBridges` (B1) |
| 5 | the mod's game tests, on every target it builds; Mycelium proves the cases and the rig compile against the game, and that the rig names no bridge class |
