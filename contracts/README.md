# Contracts: worked examples

Each package models a divide encountered by a mod. Read its SPEC for the meaning, laws, limits, and rejection matrix.
Reuse a package when it fits and helps, or author the contract your mod needs in its own Mycelium checkout or fork. The
[building guide](../docs/building-with-mycelium.md) explains the process.

[registry.json](registry.json) is the checked source for ids, packages, dependencies, status, and provenance.
This index is maintained by review. The [package guide](../CONTRACT-PACKAGE.md) distinguishes operation contracts
(outcomes and receipts) from model contracts (laws over sequences of calls).

| Contract | Meaning |
|---|---|
| [`item.component@1`](item-component/SPEC.md) | Typed item state across data components and legacy NBT, with preservation and quarantine rules (Rosetta) |
| [`item.presentation@1`](item-presentation/SPEC.md) | State-driven item assets and selectors, with stable legacy codes (the Sigil); requires `item.component@1` |
| [`block.presentation@1`](block-presentation/SPEC.md) | Independent model parts compiled from component fields, with an exact model manifest (the Mosaic) |
| [`block.catalog@1`](block-catalog/SPEC.md) | Immutable finite block registry snapshots with exact state-property domains and defaults (WorldPrompt) |
| [`state.lifecycle@1`](state-lifecycle/SPEC.md) | Slot policies across host transitions, stale-reference rejection, and release callbacks (the Threshold); requires `item.component@1` |
| [`chunk.hold@1`](chunk-hold/SPEC.md) | Independent holders over an engine that deduplicates chunk tickets |
| [`dimension.travel@2`](dimension-travel-2/SPEC.md) | Journaled return trips that recover after the process stops |
| [`dimension.travel@1`](dimension-travel/SPEC.md) | Confirmed movement between levels; superseded by `@2` |
| [`inventory.transfer@1`](flow/SPEC.md) | Transactional item transfer and its commit boundary |
| [`storage.access@1`](storage-access/SPEC.md) | Sided block storage, exact fluid units, and transactional moves; requires `inventory.transfer@1` |
| [`world.copy@1`](world-copy/SPEC.md) | Planned region copies within a write budget, including sealed copies |
| [`world.replay@1`](world-replay/SPEC.md) | Ordered resumable RLE traversal under a per-step work budget (WorldPrompt) |
| [`rule.effects@1`](rule-effects/SPEC.md) | Apply and remove scoped effects with stable identities |
| [`world.clock@1`](world-clock/SPEC.md) | Clocks, rates, timelines, markers, sync, and sleep skip; the older game is a special case |
| [`persistence.player-state@1`](player-state/SPEC.md) | Durable player state and verified schema migration chains |
| [`presentation.particle-cue@1`](cue/SPEC.md) | Particle or sound cues emitted after the outermost commit |
| [`data.catalog@1`](data-catalog/SPEC.md) | Reloadable, validated named data entries |
| [`data.generation@1`](data-generation/SPEC.md) | Atomic publication of related catalogues, with validated cross-references and stable fingerprints |
| [`random.seeded@1`](random-seeded/SPEC.md) | A seed with a stable stream across Java versions and refactors |
| [`random.keyed@1`](random-keyed/SPEC.md) | Domain-separated SHA-256 derivations and keyed HMAC draws with owned, reproducible algorithms |
| [`agent.link@1`](agent-link/SPEC.md) | Authorized requests over reconnecting external-agent links, with at-most-once handling |
| [`chat.invocation@1`](chat-invocation/SPEC.md) | Explicit chat invocations without recording the surrounding stream |
| [`chat.coverage@1`](chat-coverage/SPEC.md) | Retired stream-coverage model, retained so older builds resolve |
| [`command.authorization@1`](command-authorization/SPEC.md) | Explicit command permission grants and denials over a caller-provided default privilege (WorldPrompt) |

A reference harness passing establishes model evidence. Game behaviour requires a consumer's native implementation and
game tests. [Decisions](../docs/decisions/) record rationale and observed evidence; the [status board](../docs/STATUS.md)
lists remaining work.

## Native bridges

Bridges implement contract ports as target-selected source. Selection and packaging are described in the
[plugin reference](../docs/plugin.md#bridges); the [bridge guide](../BRIDGE-PACKAGE.md) defines their format.

| Bridge | Port | Realizations |
|---|---|---|
| [`item-carrier`](../bridges/item-carrier/bridge.json) | `StackCarrier` | Native custom data or root item NBT |
| [`item-presentation`](../bridges/item-presentation/bridge.json) | `StackPresenter` | String selectors or legacy numeric model overrides |
| [`world-clock`](../bridges/world-clock/bridge.json) | `ClockPort` | Native world clocks or emulation over day time and saved data |
| [`lifecycle`](../bridges/lifecycle/bridge.json) | `LifecyclePort` | Player and server state, bound through loader event overlays |
| [`command-authorization`](../bridges/command-authorization/bridge.json) | `AuthorizationPort` | Command-source permission decisions on 26.3 through Fabric and NeoForge overlays |
| [`block-catalog`](../bridges/block-catalog/bridge.json) | `CatalogSource` | Complete block identifiers, state-property domains, and defaults from the native registry |

`eras/minecraft.json` owns the version ranges. Linkage covers pinned game data; loader APIs and game conformance are
checked by consumers. A realization row and a compiled conformance case do not establish a successful game run.
