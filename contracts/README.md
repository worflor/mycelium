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
| `item.presentation@1` | `contracts/item-presentation` | How an item looks from its state: a modern item definition from 1.21.4, a model with overrides before; one program compiled to both, with stable legacy codes (the Sigil). Requires `item.component@1` | Somnium |
| `state.lifecycle@1` | `contracts/state-lifecycle` | What state does at each crossing (save, login, death, respawn, clone, dimension change, unload, stop, and a mod's own), with hosts no stale work can reach (the Threshold). Requires `item.component@1` | Somnium |
| `chunk.hold@1` | `contracts/chunk-hold` | Several holders keeping chunks loaded over an engine whose tickets are not counted per holder | Somnium |
| `dimension.travel@2` | `contracts/dimension-travel-2` | A trip between levels and back that survives the process dying in between | Somnium |
| `dimension.travel@1` | `contracts/dimension-travel` | Moving a player between levels, confirming arrival before reporting success (superseded by `@2`) | Somnium |
| `inventory.transfer@1` | `contracts/flow` | Transactional item transfer where loaders' handlers differ; the model for operation contracts | Osmosis |
| `world.copy@1` | `contracts/world-copy` | A bounded read of a block region, planned then written within a per-call budget | Somnium |
| `rule.effects@1` | `contracts/rule-effects` | Applying and removing a rule's effects on a bounded set of entities, with stable ids and scope | Somnium |
| `world.clock@1` | `contracts/world-clock` | Time as 26.1 models it, on every version: clocks with a rate, a pause and a fractional carry, timelines with periods and markers, edges between samples, the sleep skip; the game before 26.1 is the one clock at rate one. Pure half; the bridges are pending (decision 0018) | Cartomancy, Somnium |
| `persistence.player-state@1` | `contracts/player-state` | Durable per-player state with an explicit schema and a walk-verified migration chain | Somnium |
| `presentation.particle-cue@1` | `contracts/cue` | A particle or sound cue scheduled against a profile-resolved key, emitted at the outermost commit | Somnium |
| `data.catalog@1` | `contracts/data-catalog` | A reloadable, validated set of named data entries: what a datapack folder becomes | Somnium |
| `random.seeded@1` | `contracts/random-seeded` | A seed that means the same stream on every Java and after every refactor | Somnium |
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
