# Contracts: worked examples

Each package here crossed one divide for one mod, when that mod met the divide. They are what Mycelium's machine
produced, not the product. Read the ones near your problem to see how a divide was modelled: what the declaration is,
which laws were stated, what the rejection matrix makes wrong on purpose. Reuse one when it already says what your mod
means. Extend it when it nearly does. When nothing fits, cross the divide yourself
(`docs/building-with-mycelium.md`).

`contracts/registry.json` is the complete, checked list: id, directory, package, status, supersedes, provenance. This
index is prose, kept by review; add a row when you add a package. `CONTRACT-PACKAGE.md` is what every package is made
of. Two kinds are worth copying: an **operation** contract governs an outcome (`contracts/flow`), a **model** contract
governs a type over any sequence of calls (`contracts/chunk-hold`).

| Contract | Directory | The divide | Written for |
|---|---|---|---|
| `item.component@1` | `contracts/item-component` | Item state as typed data components from 1.20.5 and as NBT before, one fenced grammar for both (Rosetta) | Cartomancy |
| `item.presentation@1` | `contracts/item-presentation` | How an item looks from its state: a modern item definition from 1.21.4, a model with overrides before; one program compiled to both, with stable legacy codes (the Sigil). Requires `item.component@1`. Bridge `item-presentation` | Somnium |
| `block.presentation@1` | `contracts/block-presentation` | How a block looks from its block entity's data: a core and parts chosen by independent fields, each turned a quarter-turn, drawn in order; the manifest of every model the data can name is exact, and a part is enumerated apart from the others so a panel of 614,656 states costs 28 tuples a side (the Mosaic). Requires `item.presentation@1` | Cartomancy |
| `state.lifecycle@1` | `contracts/state-lifecycle` | What state does at each crossing (save, login, death, respawn, clone, dimension change, unload, stop, and a mod's own), with hosts no stale work can reach (the Threshold). Requires `item.component@1`. Bridge `lifecycle` | Somnium |
| `chunk.hold@1` | `contracts/chunk-hold` | Several holders keeping chunks loaded over an engine whose tickets are not counted per holder | Somnium |
| `dimension.travel@2` | `contracts/dimension-travel-2` | A trip between levels and back that survives the process dying in between | Somnium |
| `dimension.travel@1` | `contracts/dimension-travel` | Moving a player between levels, confirming arrival before reporting success (superseded by `@2`) | Somnium |
| `storage.access@1` | `contracts/storage-access` | Another block's storage, found by level, position and face however each loader keeps it (Fabric's transfer API, NeoForge's and Forge's capabilities, vanilla's sided containers): items and fluids simulated, then committed or released in one nested scope of an `inventory.transfer@1` transaction, so a move goes from one store to the other or not at all; fluids in thousandths of a bucket, converted exactly to each loader's unit (`eras/loaders.json`) with a remainder refused; a face reaches only what its side allows; a store that vanishes inside a move releases it. Requires `inventory.transfer@1` | Cartomancy |
| `inventory.transfer@1` | `contracts/flow` | Transactional item transfer where loaders' handlers differ; the model for operation contracts | Osmosis |
| `world.copy@1` | `contracts/world-copy` | A bounded read of a block region, planned then written within a per-call budget | Somnium |
| `rule.effects@1` | `contracts/rule-effects` | Applying and removing a rule's effects on a bounded set of entities, with stable ids and scope | Somnium |
| `world.clock@1` | `contracts/world-clock` | Time as 26.1 models it, on every version: clocks with a rate, a pause and a fractional carry, timelines with periods and markers, edges between samples, the sleep skip; the game before 26.1 is the one clock at rate one. Bridge `world-clock` (decision 0018) | Cartomancy, Somnium |
| `persistence.player-state@1` | `contracts/player-state` | Durable per-player state with an explicit schema and a walk-verified migration chain | Somnium |
| `presentation.particle-cue@1` | `contracts/cue` | A particle or sound cue scheduled against a profile-resolved key, emitted at the outermost commit | Somnium |
| `data.catalog@1` | `contracts/data-catalog` | A reloadable, validated set of named data entries: what a datapack folder becomes | Somnium |
| `data.generation@1` | `contracts/data-generation` | Several catalogs that name one another, published as one view or not at all: every entry accounted for, references resolved across catalogs (a required kind's break refuses, an optional entry leaves with what named it), one name by a fixed canonical hash of the contents and the interpreter, the live view serving meanwhile. Requires `data.catalog@1` | Cartomancy |
| `random.seeded@1` | `contracts/random-seeded` | A seed that means the same stream on every Java and after every refactor | Somnium |
| `random.keyed@1` | `contracts/random-keyed` | A value derived from public inputs that does not run back to them, and a draw no one can forecast without the world's key: owned SHA-256 and HMAC-SHA-256 over an injective, domain-separated message, compared with odds exactly | Cartomancy |
| `agent.link@1` | `contracts/agent-link` | A mod answering an out-of-process agent over a connection that drops: who may ask, at-most-once | Recon |
| `chat.invocation@1` | `contracts/chat-invocation` | Turning public chat into private requests without listening to it or recording it | Recon |
| `chat.coverage@1` | `contracts/chat-coverage` | Retired: tracking whether an owned chat stream is complete. Kept so older builds resolve it | Recon |

Proposals not yet packages are decision records: the Sigil (item presentation compiled from component state,
`docs/decisions/0012-sigil-item-presentation.md`) and the Threshold (state declares how it crosses,
`docs/decisions/0013-threshold-state-lifecycle.md`). The board is `docs/STATUS.md`.

## Bridges

A contract whose divide is Minecraft itself has a native half, a bridge (`BRIDGE-PACKAGE.md`). `item-component` has
`item-carrier`: its `StackCarrier` port is how a stack keeps the fenced root, implemented as `custom-data` (1.20.5 and later)
and `root-tag` (1.14 to 1.20.4) and proven on real stacks by conformance cases.

`world-clock`: its `ClockPort` is how a mod reads and moves time. `native-clock` (26.1 and later) maps it onto the game's own world
clocks, timelines and time packet, and writes a mod's clocks and timelines as datapack JSON; `emulated-clock` (1.17 to 1.21.11)
makes the overworld clock vanilla's `dayTime` and keeps a mod's own clocks in saved data, advanced from the mod's server-tick hook and
synced by a payload the mod delivers. Conformance cases hold the pure model to the running game on both.

`state-lifecycle`: its `LifecyclePort` is how a mod holds its state across a login, a logout, a respawn, a death, a dimension change, a save and
the server's stop. `lifecycle` (`loader-events`, 1.21 to 1.21.1 and 26.3 on) turns Fabric's, NeoForge's and Forge's events into the contract's
crossings (each loader's listeners are an overlay written for it), keeps a `HostStore` for players and one for the server, and keeps durable slots
in the overworld's saved data. Its conformance cases drive the game's own server paths with mock players, so the loader's events fire for real.
