# 0013 — The Threshold: state declares how it crosses

| | |
|---|---|
| Date | 2026-10-12 |
| Status | Proposed. First consumer: Somnium. |
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

