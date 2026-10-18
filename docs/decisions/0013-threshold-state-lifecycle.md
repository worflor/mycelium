# 0013 — The Threshold: declare state lifecycle policies

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted; pure model and `lifecycle` bridge implemented |
| Provenance | First adopted by Somnium |
| Requires | `item.component@1` for the optional component-codec adapter |
| Specification | [state.lifecycle@1](../../contracts/state-lifecycle/SPEC.md) |

## Context

Mod state lives on players, entities, levels, and servers. Each transition raises a policy question: keep, reset, drop,
migrate, copy, or move the value. Repeating those answers in loader callbacks and stop-time clear lists leaves gaps.

A consumer audit found recurring failures: session maps without cleanup, deferred work capturing obsolete player
objects, holds stranded after relog, and global counters whose missed cleanup affected unrelated players.
A persistence trait alone cannot express those policies.

## Options considered

- Keep cleanup lists and lifecycle policy in each loader's handlers.
- Declare state slots and transition policies, with a pure engine planning the effects and native adapters applying them.

## Decision

Use slots with a host scope, durability, optional codec, dependencies, and an explicit policy for every applicable
transition. A host has a stable identity and an incarnation. Deferred work captures a `HostRef`; a reference to an old
incarnation cannot resolve to its replacement.

Transitions have shapes (`SAVES`, `STAYS`, `BEGINS`, `ENDS`, `SUCCEEDS`, `FORKS`). Shape determines admissible policies.
A succession replaces one runtime host; a fork creates another host. `EXCLUSIVE` moves a value at a fork and prevents
another live copy. Session slots must drop at an end. Saving is a separate transition, before an end when needed.

The declaration check rejects missing policies, invalid policy/shape combinations, durable slots without codecs, and
dependency cycles. `SlotRegistry.of` runs it when the registry is constructed. A Gradle task that validates consumer
declarations at build time is not implemented.

The engine produces deterministic plans and applies them inside the package's transaction pattern. Abort restores the
prior state. `peek` and `read` do not create values; `materialize` and `write` are explicit mutations. Results distinguish
a gone host, an absent value, a present value, and an exclusive value held elsewhere.

Durable storage uses the contract's own `SlotCodec`. `ComponentSlotCodec` adapts component codecs through the declared
requirement. Decode failure leaves stored data untouched. Persistence and travel contracts retain their own meanings;
consumers compose their outcomes into lifecycle transitions.

## Bridge and release callbacks (2026-10-13)

The `lifecycle` bridge implements `LifecyclePort` for player and server hosts. Version ranges are in
`eras/minecraft.json`; Fabric, NeoForge, and Forge events are bound through loader overlays
([decision 0016](0016-bridges.md#loader-halves-2026-10-13)).

Adoption established details the pure model could not:

- Player respawn is a succession, including the End return path. It is not a player clone.
- Logout bindings must hear removal from the player list, after a mod's own handler has had access to the old state.
- Dropping a value may also need to release native resources. Law L9 adds `onRelease`: once per value that ceases,
  after the outermost commit. Aborted or moved values are not released. If a callback throws, remaining releases
  still run before the failure is propagated.
- Game-test mock players differ between versions. A test must establish that its mock is actually a live host.
- Saved-data locations differ between 1.21 and 26.x. Conformance searches the world's data directories accordingly.

The ordinary `write` operation does not run a release callback; replacement performed by consumer code remains its
responsibility.

## Evidence

The reference harness passed, with every rejection entry caught for its own label. Seeded transition sequences exercise
stale references, exclusivity, session cleanup, and rollback. Additional fixtures cover release callbacks.
`./gradlew check` passed, including purity, copied-pattern checks, and the era Java gates.

Recorded consumer game tests passed on Fabric, NeoForge, and Forge at 1.21.1 and 26.3. They include bridge conformance
and deferred work after host replacement. These are consumer observations, not runs reproducible from this repository.

Not established by those runs: entity or level hosts, intervening unsupported version ranges, a real client's disconnect,
a full server shutdown, or a player clone. The cases drive `PlayerList.remove` and the bridge's stop method. Loader API
references are compiled by the consumer; Mycelium's linkage gate covers the game-facing code and reports the loader files
as unlinked.

## Consequences

Policy belongs beside a state declaration. Loader handlers deliver transitions; they do not maintain independent cleanup
lists. Native resource release follows the same commit boundary as the state change. The SPEC owns the laws and labels;
unimplemented ranges and remaining evidence are tracked on the [status board](../STATUS.md).
