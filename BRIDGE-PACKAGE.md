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
│   ├── conformance/<...>/      this variant's rig for the conformance cases (how to build, copy, save a stack here)
│   ├── conformance-resources/  data files the cases need in the mod's resources (a clock is a datapack file on 26.1 and later)
│   └── overlays/<name>/        files that replace or add same-named files, for a range inside the variant
│       ├── src/
│       ├── conformance/
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
3. **Mojang names.** Variants are written in the namespaces `writtenIn` lists, and linked against the published names of
   every version they cover. A version without name data is reported, not failed.
4. **Implement the port.** The implementation class implements the contract's port; the contract's laws are held to it.
5. **Conformance on the real game.** The cases prove the laws the pure harness cannot (the stack laws, a copy, a save and
   reload, a client's copy; a clock advancing with its rate against the game's own). They are plain Java: `cases(...)` returns
   named bodies over a `Level` (and, where a law needs the server to run, `timed(...)` returns a start, a number of ticks and an end);
   the mod registers them. A case that needs data in the game (a datapack clock) has it in `conformance-resources/`, which
   `bridges { conformance() }` adds to the mod's resources beside the cases.
6. **Bring the contract.** A contract's `contracts/registry.json` row lists its bridges. A mod that references a bridge
   gets its contract and the contract's requirements.

## What keeps each rule

| Rule | Kept by |
|---|---|
| 1, 2, 4 | `myceliumRepository` (`BridgeCheck`): rows, overlay ranges, port and implementation named |
| 3 | `myceliumBridgeLinkage`: each variant, with each overlay, compiled and linked against every version with name data |
| 4 (an override) | `myceliumBridges` (B1) |
| 5 | the mod's game tests, on every target it builds |
