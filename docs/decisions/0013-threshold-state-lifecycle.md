# 0013 — The Threshold: state declares how it crosses

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Accepted (reference model and rejection matrix: `contracts/state-lifecycle`; kept by `myceliumVerify`). First consumer: Somnium. |
| Builds on | `item.component@1` (its codecs carry durable values), `persistence.player-state@1` (stays the storage port: the medium, not the policy) |

## Context

State lives on players, entities, levels, and servers. Those hosts cross save, login, death, respawn, dimension,
unload, and shutdown boundaries; a mod may define its own transitions. Repeated event handlers make state policy
hard to inspect and keep consistent. Declare that policy once and realize it through platform events.

## Decision

`state.lifecycle@1`: state is declared in **slots**, each with a scope, a durability, a codec where durable, and a
**policy for every transition** in its scope. A pure engine turns a transition on a host into an ordered plan of effects;
the platform performs the plan. Hosts are referred to by identity with an **incarnation**, so work aimed at a host that has
since logged out, respawned or reloaded is refused by construction.

### Identity

- `HostId(kind, world, id)`: kind is player, entity, level or server; `world` names the save; `id` is the stable token
  (UUID, dimension key). Pure: no Minecraft types.
- `Incarnation`: a counter per host, advanced by every transition that replaces the runtime object (login, respawn, clone,
  entity reload, level reload).
- `HostRef(hostId, incarnation)`: what scheduled or deferred work captures. Resolving a ref whose incarnation is not the
  host's current one yields *gone*, never the new object. A closure can no longer act on a stale player.

### Slots


### The engine

`plan(transition, host, registry) -> Plan`: the ordered effects on every slot in the host's scope (keep, drop, reset,
migrate, copy, move). Ordering is declared dependency order, then slot id. Applying a plan is one transaction (the
package's `Tx` pattern): every effect registers its undo; abort leaves no partial crossing.

A **declaration compiler** (build time, via the Gradle plugin) checks the consumer's slot registry: every slot has a policy
for every transition its scope admits (no silent default), `EXCLUSIVE` only on transitions that create a second host,
`DURABLE` slots have a codec, dependency order is acyclic. A missing policy is a build error naming the slot and the
transition.

## Laws

| Law | Statement |
|---|---|
| L1 | **Totality.** A registry is accepted only if every slot declares a policy for every transition its scope admits. |
| L2 | **No stale hosts.** A `HostRef` whose incarnation is not current resolves to *gone*; no effect reaches the new incarnation through it. |
| L3 | **Exclusivity.** Over any sequence of transitions, an `EXCLUSIVE` slot's value exists on at most one host. |
| L4 | **Sessions end.** After a host's incarnation ends (logout, removal, unload, server stop), none of its `SESSION` slots holds a value. No clear lists. |
| L5 | **Peek is pure.** `peek` and `read` never create a value; only `materialize` and declared effects do. |
| L6 | **Durable round trip.** A `DURABLE` slot's value survives save and load equal under its codec's semantic equality. |
| L7 | **Deterministic plans.** The same transition, host state and registry produce the same plan, in the same order. |
| L8 | **Atomic crossings.** An aborted plan leaves every slot as it was before the transition. |

Laws L2 to L4 and L8 are checked over seeded random sequences of transitions (logins, deaths, clones, reloads, aborts), not
only fixed scenarios.

## Rejection matrix

| Fixture | Wrong | Caught by |
|---|---|---|
| `ForgetfulEngine` | ignores logout for session slots | L4 |
| `IdOnlyResolver` | resolves a ref by host id, ignoring incarnation | L2 |
| `CloningExclusive` | copies an `EXCLUSIVE` slot on clone | L3 |
| `MaterializingPeek` | `peek` creates the default | L5 |
| `DefaultPolicy` | accepts a registry with a missing policy, treating it as keep | L1 |
| `HalfCrossing` | leaves earlier effects applied when a later one aborts | L8 |
| `NoOpEngine` | plans nothing | L4, L6 |

## Relation to existing contracts


## First proof (Somnium)


## Later


## Decided in building

The decision above stands. Where it was silent or ambiguous, the package decided, and `contracts/state-lifecycle/SPEC.md`
is normative.

- **Transitions have a shape** (`SAVES`, `STAYS`, `BEGINS`, `ENDS`, `SUCCEEDS`, `FORKS`), which is what makes "host-creating"
  checkable and fixes what each policy means. The decision listed `clone` both as a transition that advances the
  incarnation and as one that creates a second host; those are two crossings. A player's replacement on respawn (a loader's
  "copy from the old player") is `SUCCEEDS`: same host, new incarnation, old one ends. `clone` is `FORKS`: a new host id,
  both live. `EXCLUSIVE` therefore has a second host to move to, and a stale-incarnation cell is not "a second host".
- **Which policy is admissible where** is one table (`Shape.admits`) and part of the declaration check: a session slot must
  `DROP` at every end (L4 stated at declaration, so no engine override is silent), `KEEP` is the only policy at a save,
  `COPY`, `REGENERATE` and `EXCLUSIVE` only at a fork. Extra rules the decision did not state: an exclusive slot declares
  nothing that would create a value elsewhere (`RESET`, `REGENERATE`, `COPY`, `MIGRATE` at a fork), and a policy for a
  transition outside the slot's scope is an error, not noise.
- **`DROP` means gone everywhere** (live and stored); `KEEP` at an end releases the live copy and storage keeps the form.
  Saving is its own transition: an end does not write. An adapter fires `mycelium:save` first.
- **Access has a fourth operation, `write`**, beside `peek`, `read` and `materialize`, since setting a value is not creating
  a default. Results are one sealed type (`Gone`, `Absent`, `Present`, `Held`) so a gone host is never read as an absent value.
- **The `Tx` is the contract's own copy** of the transaction pattern, kept identical by `contracts/patterns.json`.
- **The codec seam.** The decision has durable slots encoded with `item.component@1`'s codecs. A model never imports another
  contract (decision 0005), so a durable slot's codec is this contract's `SlotCodec` (encode to an opaque immutable stored
  form, decode, the codec's own equality). `ComponentSlotCodec` adapts a component codec and imports its model, which the
  registry row declares with `requires` (the minimal form of the mechanism the item-presentation work defines at merge).
  A decode failure aborts the crossing, leaving the stored form untouched.
- **Law labels.** The declaration rules beyond totality sit under L1. Two fixtures were added to the seven so that every law
  is proven: `ForgetfulStorage` (L6) and `RotatingEngine` (L7).
- **The build-time compiler** is not a plugin task yet. `SlotRegistry.of` runs the same check when the mod's registry is
  built, so a missing policy is an exception naming the slot and the transition at start-up and in the mod's own tests.
  A Gradle task that runs it on a consumer's declarations waits for a consumer that needs it at build time.

## The bridge, as built (Somnium, 2026-10-13)

`bridges/lifecycle` implements `LifecyclePort` (SPEC section 5) on Minecraft 1.21 to 1.21.1 and 26.3 on, for Fabric, NeoForge and
Forge; decision 0016 ("Loader halves") says how a bridge is split by loader. Somnium adopted it (`docs/design/the-threshold-in-somnium.md` there)
and nine game tests (the conformance cases among them) pass on all six targets (Fabric, NeoForge and Forge on 1.21.1 and 26.3). What the game
taught, that the model could not:

- **Minecraft has no player clone.** A death and the End both go through the respawn copy, so `mycelium:clone` is never fired for a player.
- **A respawn is one event on every loader** (the copy from the old player), but the logout is not one: Fabric's `DISCONNECT` is the connection's, the
  game's `PlayerList.remove` is the list's. The bridge hears the list's (Fabric `ServerPlayerEvents.LEAVE`, the loaders' logout events), in
  the phase after a mod's own, so a mod can act on a player in its own handler before the crossing drops their values.
- **A session slot's end cannot clean the world.** The lifecycle drops a value; it does not know that the value is a bunch of display entities. Somnium
  calls a release in its own logout and stop handlers, before the bridge's. An end observer on a session slot (run just before the drop, with
  the value) would take those hooks out of every consumer. Not built; the first thing the next consumer should ask for.
- **Mock players differ by version.** On 26.x the game test's mock player is placed in the player list and so is a host; on 1.21.1 it is not. A mod
  whose tests use mock players keeps a value for an object the game never placed weakly by the object (Somnium's `Held`), which is also where a
  write that arrives after the logout crossing lands (the game awards a stat as it removes a player), so nothing outlives the object.
- **The saved data lives in a different place.** The overworld's data folder is the world's on 1.21 and the dimension's (`dimensions/minecraft/overworld/data`,
  under the namespace folder `mycelium`) on 26.x; the conformance case looks through the world folder.
- **A scheduled task that holds a ref reaches nothing after a respawn.** Somnium's `Fx.later(ticks, owner, beat)` is that case, driven in a game test (`threshold_delayed_beats`)
  with a player who stays, one who leaves and one who dies.

Not run in a game: a clone, a real client's disconnect, a real server stop (the cases drive `PlayerList.remove` and the bridge's stop), the
versions between 1.21.1 and 26.3 (their rows say `none`).

## Evidence

- `bash run-contracts.sh state-lifecycle`: the reference clean, every entry of the matrix caught for its own label (the
  seven fixtures above and the two added), the do-nothing engine among them. L2, L3, L4 and L8 are checked after every step of
  seeded random sequences; L1, L5, L6 and L7 in fixed scenarios.
- `./gradlew check`: the repository check (shape, model ownership, the copied transaction), purity, and the harness on every
  Java an era runs on.
- Not claimed: any behaviour in a real game. Somnium's gametests (the first proof above) are that evidence.
