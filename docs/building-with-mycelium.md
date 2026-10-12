# Building with Mycelium

You are working on a mod and it has met a **divide**: something that is true on one version or loader and different on
another. This is what you do. Mycelium is not a shelf of parts to choose from; it is the machine you use to cross the
divide once, correctly, for every target (`docs/decisions/0015-a-compiler-you-build-with.md`).

## Recognise a divide

You are looking at one when the mod has, or is about to have:

- a Stonecutter `//? if`, a branch per loader, or a helper whose body is a version switch (`Compat.readLong`);
- the same fact written twice (a modern asset and a legacy asset; a table in JSON and arithmetic in Java that must agree);
- a value whose meaning changes across a wall in `eras/minecraft.json` (item NBT to components at 1.20.5, item model
  definitions at 1.21.4, `ResourceLocation` to `Identifier` at 1.21.11);
- a lifecycle the loaders expose differently (respawn, clone, dimension change, unload);
- a guarantee the mod relies on but nothing checks (state that must not survive a logout, a sprite that must match state).

One `//? if` around a renamed method is not worth a compiler: Stonecutter is the right tool for a rename. A divide is worth
modelling when it carries *meaning* (what the mod stores, shows, keeps, or promises), when it repeats, or when getting it
wrong is silent.

## Cross it

1. **Say what the mod means, without Minecraft.** Write the pure model: the declaration a mod author writes once (a
   component's record, a presentation program, a slot and its policies). Types, not maps of strings.
2. **Write the laws.** What must be true on every target: round trips, agreement between targets, stability across
   additions, ordering, safety under corruption. Each law is a sentence a do-nothing implementation breaks.
3. **Write the broken implementations first.** One per law, ideally the bug the mod would really have made, and a `NoOp`.
   The harness must catch every one (`CONTRACT-PACKAGE.md`). If you cannot write a broken implementation, the law is not
   a law yet.
4. **Put version knowledge in data.** What changes at which version goes in `eras/` as a row the compiler reads, never as
   numbers in code.
5. **Compile, don't branch.** Where the mod needs per-target artefacts (assets, generated tables, plans), a Gradle task
   in the plugin generates them from the declaration for the target's version. The mod's platform layer reads the
   generated plan; it does not re-derive it.
6. **Build on what exists.** If another contract already models part of the divide, require it
   (`docs/decisions/0014-contracts-compose-by-declared-requirement.md`) instead of restating it. If one nearly fits,
   extend it: additively within `@N` (a new law, a new optional field, a new fixture), or as a new major when the meaning
   changes.
7. **Use it in the mod in the same piece of work.** Import it, replace the hand-written split, delete the duplicate, and
   prove it on every target with the mod's gametests. That consumer evidence is what turns design-time laws into a claim
   about the game.
8. **Gate and record.** `./gradlew check` green here, the mod's own gates green there, a `CHANGELOG.md` entry, and a
   decision record if you chose between real alternatives.

## Worked examples

Each package in `contracts/` crossed one divide for one mod. Read the ones near your problem before you start.

| Package | The divide | Written for |
|---|---|---|
| `item.component@1` (Rosetta) | item state as typed components on 1.20.5+ and as NBT before; one fenced grammar for both | Cartomancy, then Somnium |
| `chunk.hold@1` | keeping chunks loaded with tickets that differ by version and loader | Somnium |
| `dimension.travel@2` | moving a player between levels with rescue semantics | Somnium |
| `inventory.transfer@1` | transactional item transfer where loaders' handlers differ | Osmosis |
| `agent.link@1`, `chat.invocation@1` | a server assistant's link and its chat surface | Recon |

`contracts/registry.json` is the full list, with provenance. Packages in progress are recorded in `docs/decisions/`
(0012, the Sigil: item presentation; 0013, the Threshold: state lifecycles).

## What you may do here

Change contracts, the harness, the plugin, eras and documents as the work needs, inside the gates (`AGENTS.md`). Ask
first only for what is irreversible or reaches another consumer: changing an existing package's meaning in place (that is a
new major instead), adding a dependency, publishing, or editing another repository.
