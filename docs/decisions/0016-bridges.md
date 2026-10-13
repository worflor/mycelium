# 0016 — Bridges: Mycelium carries the native half

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted as direction (Wolf, 2026-10-12: "the mod is the pure intent. Mycelium takes that intent and weaves it into both game loaders and all those things... supporting earlier versions should be as easy as saying, well, it just maps here now. Or it should be as easy as saying, well, it just objectively cannot work"). First bridge: the item carrier for `item.component@1`. |
| Goes beyond | `DESIGN.md` §5 (the tower puts every provider in the consumer) and decision 0015 |

## Context

The founding design gives Mycelium the pure half of a divide (models, laws, era data, compilers) and leaves the native half,
the providers, to each mod. Building with Rosetta showed what that costs. Somnium's `Carrier` is 359 lines of which none are
about dreams: converting Mojang tags to Rosetta's model per version, reading and writing the fenced compound in
`custom_data`, keeping other mods' keys, migrating old keys. Cartomancy will write the same class; so will every mod after
it. And every new Minecraft version means editing every one of them. Supporting an older version is a port of each mod,
not a fact about the game.

The native half of a divide is usually as general as the pure half. The part of a provider that is truly the mod's own is
small; the rest is "how this version does that thing", which is knowledge about the game, and belongs with the era data
that already describes the game.

## Decision

Mycelium ships **bridges**: native adapters, as source, one variant per realization of a divide, imported into the mod like
models (private, under the mod's namespace, no runtime), chosen by the target's version from data, and proven against the
real game. The mod states its intent through the contract; the bridge carries it onto whatever the target is.

### Shape

- A bridge lives in `bridges/<slug>/`, implementing a **port** its contract declares (an interface in the contract's model,
  pure). Each **variant** is a source set for one realization (`custom-data`, `root-tag`; `native-clock`, `emulated-clock`).
- Which variant a version draws is a row in the era data's **realization tables** (the table `item.presentation` already
  uses, generalised: one table per divide, every real version in exactly one range, `none` where the game cannot do it).
  The plugin holds no version numbers.
- Variants are written against Mojang names. Loaders' own toolchains remap them like any mod source. Where a loader truly
  differs, a variant may split by loader the same way, from data.
- `mycelium { contracts("item.component@1") }` brings the contract's bridges with it; `bridges { }` names which are wanted
  when a mod wants only some.

### Offer, never police

A bridge is an offer. The mod can:
- take it as it is;
- **override** a variant with its own class (`bridges { override("item-carrier", "com.example.MyCarrier") }`), which must
  implement the same port; the contract's laws still apply to it, nothing else does;
- **extend** it: bridges are open classes with documented seams, not sealed boxes;
- skip it and write native code directly. Native stays first-class (`DESIGN.md` §4).

The only things enforced are the things that prevent silent breakage: a variant's references exist in the game it claims
(linkage), and the port's laws hold (the harness, and the conformance tests below).

### Minecraft as the checker

Every bridge is proven in three places:

1. **Linkage, in Mycelium's own gate.** Each variant's every class, method and field reference is resolved against the
   published names of every version its realization row covers (`myceliumLinkage`'s machinery, `eras/names.json`). A
   variant that names something a version lacks fails here, before any mod builds it.
2. **Conformance gametests, shipped with the bridge.** A bridge carries game tests for its port (round trip on a real
   `ItemStack`, stacking, sync, save and reload) that a mod imports into its own test sources. The real game runs them on
   every target the mod builds: Minecraft is the compiler and the checker.
3. **A reference lane in Mycelium** (later): a minimal consumer per realization that boots the real game and runs the
   conformance tests, so a bridge is proven before any mod adopts it.

### What a mod's range becomes

Each realization row says where each variant works and where nothing can. A mod's reachable versions are then derived, not
guessed: the intersection of the rows of every bridge and compiler it uses. `myceliumRange` reports, per version, either
"maps here (variant X)" or "cannot: `item.presentation` has no realization before 1.14". Advisory by default; a failure only
for versions the mod claims in `range { }`.

## Consequences

- Somnium's `Carrier` moves to `bridges/item-carrier/` (variants `custom-data` from 1.20.5, `root-tag` before) and shrinks in
  the mod to its component list. Adding 1.20.1 to a Rosetta mod becomes a build target, not a rewrite.
- The next contracts are written with their bridges from the start: `world.clock@1` (`native-clock` from 26.1,
  `emulated-clock` before), the Sigil's runtime selector (strings from 1.21.4, floats before), the Threshold's lifecycle
  bridge (each loader's events into transitions).
- `docs/architecture.md`'s tower gains the bridge layer between contracts and the mod's own providers; `docs/building-with-
  mycelium.md` adds "write the bridge" as a step; `CONTRACT-PACKAGE.md` gains the bridge package shape.
- Bridges are code that touches Minecraft, so the purity rules stay with the models; bridges have their own rules: Mojang
  names, a port to implement, linkage on their whole range, conformance tests shipped beside them.

## Machinery (specified 2026-10-12, before the build)

- **Layout.** `bridges/<slug>/bridge.json` (id, the contract and port it implements, its realization table's name, its
  variants), `bridges/<slug>/<variant>/src/` (the variant's source), `bridges/<slug>/<variant>/overlays/<range>/` (files
  that replace same-named files of the variant for a range of versions inside it: an API that shifts inside one realization,
  such as `CompoundTag`'s getters at 1.21.5 or `CustomData`'s smaller surface at 26.1, is an overlay, not a new variant),
  `bridges/<slug>/conformance/` (game tests for the port, written against the port, not a variant).
- **Realization tables.** `eras/minecraft.json` holds one table per divide (`item.presentation` already exists), each row a
  range and a variant (or `none` with the reason). Overlay ranges are rows of the bridge's own `bridge.json`, held by the
  repository check to fall inside their variant's range.
- **Import.** For the target version, the plugin selects the variant and its overlays, rewrites packages under the mod's
  namespace (as models are), and adds the result to the mod's sources; conformance tests go to the mod's game-test sources
  when the mod asks (`bridges { conformance() }`). An `override(slug, class)` drops the bridge's variant and checks only that
  the class implements the port.
- **Proof in Mycelium's gate.** Every variant, with each overlay, is held to linkage against the published names of the
  versions it covers (`eras/names.json`; versions without name data are reported, not failed). The repository check proves
  every bridge's realization rows cover each version exactly once.
- **First bridge: `item-carrier`.** Port: `FencedCarrier`'s root in and out of a stack. Variants `custom-data` (1.20.5 on,
  overlays at 1.21.5 and 26.1) and `root-tag` (1.14 to 1.20.4; a null tag when empty, since a null and an empty tag do not
  stack there). Conformance: round trip on a real `ItemStack`, a cleared stack equal to a plain one and stacking with it,
  other keys kept, quarantine refused not lost, sync to a client copy, save and reload.

## Evidence and what the build decided where this was silent (2026-10-13)

- Built as specified: `bridges/item-carrier/`, the realization tables, the import, `override`, `conformance()`, the repository
  check and the linkage gate; `item.component@1` gained the `StackCarrier` port (additive, SPEC section 5.7).
- Overlay layout is `overlays/<name>/{src,conformance}/`, and an overlay may add files as well as replace them. Overlays may not
  overlap each other; each lies inside its variant.
- A variant may carry its own `conformance/` rig (how to build, copy, save a stack there); the cases stay written against the port.
- `bridge.json` has `writtenIn`, the name namespaces the source is written in; the names check counts a pin as read for it.
- Selection: a source referencing `<namespace>.bridges.<slug>` or `bridges { use(...) }` brings a bridge and its contract.
  `override` imports nothing and requires the class to implement the port (B1); no realization on the target is B2 (warn).
- Conformance goes to the `gametest` source set when present, else `main`, or the one `conformance("name")` names.
- Linkage compiles first, then checks references: each variant with the overlays covering a group of versions is compiled
  against the newest version with data, then its references are linked on every version with data. The compile needs a javac as
  new as the newest era's Java.
- `custom-data`'s 1.21.5 overlay covers 26.x: nothing in this code changed at 26.1, so the 26.1 overlay expected above was not needed.
- Correction: root-tag's 1.14 needs an overlay of its own (1.14's tags have constructors, not `valueOf`); and
  `ItemStack.isSameItemSameTags` is absent before 1.17, so its rig uses `ItemStack.matches`.
- Linkage results: custom-data base 1.20.5 to 1.21.4 (1.21.2 has no data); custom-data with the 1.21.5 overlay on 1.21.5 to 26.3
  (no data for 1.21.6, 1.21.7, 1.21.9); root-tag 1.15 to 1.20.4 on the eight pinned versions; root-tag with the 1.14 overlay on
  1.14.4. Mojang names start at 1.14.4, so 1.14 to 1.14.3 are reported, not failed.
- A variant may carry `conformance-resources/` (files its cases need in the game's resources: on 26.1 and later a clock is a datapack
  file). `myceliumBridgeImport` lays them under `build/generated/resources/mycelium/conformance` beside the cases and the conformance
  source set takes that directory; a mod without cases gets none. `world-clock` is the first user (decision 0018); its overlays are by
  saved-data shape, rule name and tick rate manager.
- Conformance data is test data: the jar a mod ships leaves out the files the import laid under `.../mycelium/conformance`
  (they still reach the game-test run, which reads the compiled output directories). `bridges { shipConformanceResources() }` keeps
  them; `conformanceClassesOutOfJar()` also leaves the conformance classes out, for a mod whose tests are not in the main source
  set. `item-presentation` (decision 0012, "Somnium, adopted") is the second bridge with cases; Somnium's shipping jar no longer
  holds the clock's datapack files.
