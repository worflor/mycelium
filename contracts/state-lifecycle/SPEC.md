# state.lifecycle@1

State declares how it crosses. A mod's state lives on hosts (a player, an entity, a level, the server), and the game moves
hosts through transitions: save and load, login and logout, death, respawn, clone, unload, removal, server stop, plus the
mod's own. Left to event subscribers and clear lists, the answer to "what happens to this state at this crossing" is
written by hand, per loader, and decays: a static map with a `clear()` three entry points must remember, a delayed task
holding a player object across a logout, a map keyed by a player object so that a relog strands what it held, a counter
that switches a guard off for everyone when one decrement is missed, a respawn and a clone nobody subscribed to.

It was found in Somnium, whose map of its session state showed all of those. The fix is general, so it is a contract.

| | |
|---|---|
| Contract id | `state.lifecycle@1` (implementation namespace `mycelium.state_lifecycle.v1`) |
| Kind | Model contract (`../../CONTRACT-PACKAGE.md`): laws over any sequence of crossings, with a transaction for atomicity |
| Requires | `item.component@1`, its model only: a durable slot's codec can be made from a component codec (`ComponentSlotCodec`) |
| Storage | `persistence.player-state@1` stays the storage port: the medium and the schema chain. This contract is the policy |
| Reference model | `src/` : pure Java 17, no Minecraft or loader imports |

## 1. The model

**Identity.** `HostId(kind, world, id)` names a host: its kind (player, entity, level, server), the save it belongs to, and
the token that names it there. `Incarnation` counts the runtime objects a host has had, from 1; every crossing that
replaces the object advances it. `HostRef(host, incarnation)` is what scheduled or deferred work captures. Resolving a ref
(`HostStore.resolve`) gives the object only while its incarnation is the host's current one, and otherwise gives nothing,
never the host's next object. A ref is valid within one run of the server and is not persisted.

**Transitions are data.** `TransitionId` is `namespace:path`; the platform's are the `mycelium` namespace and a consumer may
not declare into it. A `Transition` has a `Shape`, which fixes what a policy can mean there, and the host kinds it applies to.

| Shape | What it does to its host | Platform transitions |
|---|---|---|
| `SAVES` | state is written to storage; the object stays | `mycelium:save` (every kind) |
| `STAYS` | same object, same incarnation | `mycelium:death`, `mycelium:dimension_change` (player, entity) |
| `BEGINS` | a host comes to exist with a new incarnation, from storage | `mycelium:login` (player), `mycelium:load` (entity, level, server) |
| `ENDS` | the incarnation ends | `mycelium:logout` (player), `mycelium:unload` (entity, level), `mycelium:remove` (entity), `mycelium:server_stop` (every kind) |
| `SUCCEEDS` | a new incarnation of the same host takes over, the old one ends | `mycelium:respawn` (player) |
| `FORKS` | a second host, with an identity of its own, is made from a first, which lives on | `mycelium:clone` (player, entity) |

A loader's "copy from the old player" event on respawn is the `SUCCEEDS` crossing. A consumer declares its own transitions
(`Transitions.builder().declare("example:drift", Shape.STAYS, HostKind.PLAYER)`), each with the host kinds it applies to.

**Slots.** `Slot<T>` has an id, a scope (a host kind), a durability, a codec if durable, an initial value, a policy for
each transition, and the slots whose effects must come first. Values are immutable. `SESSION` lives in memory for as long as
its host's current incarnation. `DURABLE` is saved with the host, encoded by a `SlotCodec`, whose stored form is opaque to
this contract and must have value equality.

**Policies**, per transition, with no default:

| Policy | Meaning |
|---|---|
| `KEEP` | stays as it is. At a save a durable value is written, at a begin it is loaded, at an end its live copy is released and storage keeps it, at a succession it moves to the new incarnation |
| `DROP` | ceases to exist for that host, live and stored |
| `RESET` | becomes the slot's initial value |
| `MIGRATE(f)` | becomes `f(old)` where there is an old value |
| `COPY` | at a fork: the new host gets an equal value, both keep theirs |
| `REGENERATE(g)` | at a fork: the new host gets `g(its id)`, independent of the first |
| `EXCLUSIVE` | at a fork: the value moves, and exists on exactly one of the two afterwards (its stored form moves with it) |

`Shape.admits` is the table of which policy means something where: `SAVES` takes `KEEP` only; `STAYS` and `SUCCEEDS` take
`KEEP`, `DROP`, `RESET`, `MIGRATE`; `BEGINS` takes those for a durable slot and `KEEP`, `RESET` for a session one (a session
value is absent at a begin by definition); `ENDS` takes only `DROP` for a session slot, which is law L4 stated at
declaration, and `KEEP`, `DROP` for a durable one; `FORKS` takes everything but `KEEP`. A slot that declares `EXCLUSIVE`
anywhere is an exclusive slot, and declares nothing that would put its value on a second host: no `RESET`, `REGENERATE` or
`COPY`, and at a fork only `EXCLUSIVE` or `DROP`.

**Access** is four operations on a `HostStore`, never conflated, and each answers with a `Reading`: `Gone` (the ref is not
current), `Absent`, `Present(value)`, or `Held(host)` (an exclusive value already exists elsewhere). `peek` finds a value or
its absence and never creates. `read` gives the effective value, the declared initial value when absent, and never creates.
`materialize` creates the initial value if absent: a write. `write` sets a value: a write. Writes take a `Tx` and register
their undo.

**The engine.** A `Crossing` says which host or hosts a transition acts on (`Stay`, `Begin`, `End`, `Succeed`, `Fork`, with
the platform's runtime object where one comes into being). `Crossings.plan(transition, crossing, store, registry)` is a pure
function to a `Plan`: an ordered list of `Effect`s, host effects (begin, end) and one group of slot effects per slot of the
host's kind, in registry order. `Crossings.apply(plan, store, tx)` performs it, registering every undo in the `Tx`;
`StandardCrossings` is the contract's engine. A plan whose hosts have changed since it was made is refused before anything
changes.

**The declaration check.** `SlotRegistry.compile(transitions, slots)`, by `StandardCompiler`, returns `Accepted(registry)` or
`Rejected(diagnostics)`, and reports every problem at once, each naming the slot and (where there is one) the transition:
`MISSING_POLICY`, `UNKNOWN_TRANSITION`, `OUT_OF_SCOPE_POLICY`, `INADMISSIBLE_POLICY`, `EXCLUSIVE_CREATES_VALUE`,
`DURABLE_WITHOUT_CODEC`, `DUPLICATE_SLOT`, `UNKNOWN_DEPENDENCY`, `CROSS_SCOPE_DEPENDENCY`, `DEPENDENCY_CYCLE`. Only a
compiler makes a `SlotRegistry`, so holding one is the proof. Order is fixed there: within a scope, dependencies first, then
slot id; declaration order never matters.

**The seam to storage.** `HostStore` is what a provider keeps: live incarnations, the value cell of each incarnation, and
the stored form of each durable value per host id. It is where `persistence.player-state@1` plugs in; this contract does not
import it. Ending an incarnation does not remove its cells: the engine does, so a cell left behind is visible, as the map entry
keyed by a dead player was.

**`Tx`** is the transaction pattern every package copies (`../../contracts/patterns.json`).

## 2. Laws

| Law | Statement |
|---|---|
| L1 | **Totality.** A registry is accepted only if every slot declares a policy for every transition its scope admits, none for any other, every policy is admissible on its transition's shape, durable slots have codecs, and dependencies name slots of the same scope and are acyclic. A plan covers every slot of its host's kind. |
| L2 | **No stale hosts.** A `HostRef` whose incarnation is not current resolves to nothing, and a read, a write or a plan aimed through it reaches nothing of the new incarnation. Incarnations advance on every begin and succession. |
| L3 | **Exclusivity.** Over any sequence of transitions, an exclusive slot's value exists on at most one host, live or stored. `materialize` and `write` refuse a second holder. |
| L4 | **Sessions end.** After a host's incarnation ends (logout, remove, unload, server stop, or the old side of a respawn), the host is no longer current and none of its session slots holds a value. No clear lists. |
| L5 | **Peek is pure.** `peek` and `read` never create or change a value; only `materialize`, `write` and declared effects do. |
| L6 | **Durable round trip.** A durable slot's value survives save, end, restart and begin, equal under its codec's own equality. A stored form that does not decode stops the crossing and changes nothing. |
| L7 | **Deterministic plans.** The same transition, host state and registry give the same plan in the same order, whatever order the slots were declared in; order is dependencies first, then slot id. |
| L8 | **Atomic crossings.** A plan applied inside a `Tx` that is aborted, or that fails part way, leaves every cell, stored form, live host and incarnation counter as it was before. |

L2, L3, L4 and L8 are checked over seeded random sequences of crossings (logins, logouts, deaths, respawns, clones, reloads,
saves, consumer transitions, stops and restarts, plain accesses, and crossings that are aborted or fail half way), after
every step, as well as in fixed scenarios.

## 3. Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `ForgetfulEngine` | ignores logout (every end) for session slots | L4 (`L4:session-slots-empty`) |
| `IdOnlyResolver` | resolves a ref by host id, ignoring the incarnation | L2 (`L2:stale-ref-gone`) |
| `CloningExclusive` | copies an exclusive slot onto a clone instead of moving it | L3 (`L3:exclusive-at-most-one`) |
| `MaterializingPeek` | `peek` creates the default | L5 (`L5:peek-pure`) |
| `DefaultPolicy` | accepts a slot with a missing policy, treating it as keep | L1 (`L1:missing-policy-rejected`) |
| `HalfCrossing` | applies a plan into a transaction of its own, so an abort leaves earlier effects applied | L8 (`L8:aborted-crossing-restores`) |
| `ForgetfulStorage` | accepts a stored form and keeps nothing | L6 (`L6:durable-round-trip`) |
| `RotatingEngine` | plans the right effects in an order that depends on how many plans it has made | L7 (`L7:same-input-same-plan`) |
| `SkipsNoOpEffects` | leaves the KEEP effects out of a plan, so it no longer covers every slot of the host | L1 (`L1:plan-covers-scope`) |
| `PreIncrementedCounter` | hands out 2 as a host's first incarnation | L1 (`L1:first-incarnation`) |
| `RefusesSoundDeclarations` | (`OverStrictCompiler`) demands a policy for every declared transition, forgetting the scope filter, so a sound declaration is refused | L1 (`L1:complete-accepted`) |
| `TerseDiagnostics` | rejects with the right codes but messages that name neither slot nor transition | L1 (`L1:diagnostic-names-slot-and-transition`) |
| `AcceptsUnknownTransition` | (`LaxCompiler`) ignores a policy for a transition nobody declared | L1 (`L1:unknown-transition-rejected`) |
| `AcceptsOutOfScopePolicy` | (`LaxCompiler`) ignores a policy for a transition that does not apply to the slot's scope | L1 (`L1:out-of-scope-rejected`) |
| `AcceptsCopyAwayFromForks` | (`LaxCompiler`) accepts COPY where no host is created | L1 (`L1:copy-only-on-forks`) |
| `AcceptsRegenerateAwayFromForks` | (`LaxCompiler`) accepts REGENERATE where no host is created | L1 (`L1:regenerate-only-on-forks`) |
| `AcceptsExclusiveAwayFromForks` | (`LaxCompiler`) accepts EXCLUSIVE where no host is created | L1 (`L1:exclusive-only-on-forks`) |
| `AcceptsSessionKeptPastEnd` | (`LaxCompiler`) lets a session slot keep its value at an end | L1 (`L1:session-dropped-at-end`) |
| `AcceptsDurableWithoutCodec` | (`LaxCompiler`) accepts a durable slot with no codec | L1 (`L1:durable-needs-codec`) |
| `AcceptsDependencyCycle` | (`LaxCompiler`) accepts slots in a dependency cycle | L1 (`L1:cycle-rejected`) |
| `AcceptsUnknownDependency` | (`LaxCompiler`) ignores a dependency on a slot nobody declared | L1 (`L1:unknown-dependency`) |
| `AcceptsCrossScopeDependency` | (`LaxCompiler`) ignores a dependency on a slot of another scope | L1 (`L1:cross-scope-dependency`) |
| `AcceptsDuplicateSlot` | (`LaxCompiler`) drops a second declaration of a slot id without a word | L1 (`L1:duplicate-slot-rejected`) |
| `AcceptsExclusiveThatCreatesValues` | (`LaxCompiler`) lets an exclusive slot declare RESET, REGENERATE or COPY | L1 (`L1:exclusive-creates-value`) |
| `ReusedIncarnation` | gives a host that comes back the incarnation number of the one that ended | L2 (`L2:incarnation-advances`) |
| `TombstonedResolver` | resolves no ref of a host id that ever ended, so a relogged player's fresh ref is dead | L2 (`L2:live-ref-resolves`) |
| `WriteThroughHostId` | a write through a stale ref lands in the host's current incarnation | L2 (`L2:stale-access-gone`) |
| `SwallowsStaleHosts` | answers a plan or an apply aimed at a stale incarnation with a quiet no-op | L2 (`L2:stale-plan-refused`) |
| `AnyoneOnlineHolds` | takes any other live host to be the holder of an exclusive slot, so nobody can be first | L3 (`L3:first-holder-created`) |
| `UncheckedExclusive` | materialize and write of an exclusive slot never ask whether another host holds it | L3 (`L3:second-holder-refused`) |
| `StrandedExclusive` | at a fork the exclusive value stays with the source | L3 (`L3:clone-takes-the-token`) |
| `MovingCopy` | COPY implemented as a move, so the source loses its value | L3 (`L3:copy-is-on-both`) |
| `CloneEndsSource` | a fork ends the source's incarnation as a respawn does | L3 (`L3:fixture-reads`) |
| `GlobalIncarnationCounter` | counts incarnations across all hosts, so a late host's first is not 1 | L3 (`L3:fork-target-starts-at-first-incarnation`) |
| `RefusesEveryHolder` | answers Held to every exclusive create or write, so no token ever exists in the sequences | L3 (`L3:token-exercised`) |
| `SessionLostOnRespawn` | releases a session value at a respawn instead of carrying it to the new incarnation | L4 (`L4:carried-session-value`) |
| `PeekReadsDefault` | `peek` answers the initial value for an absent value | L5 (`L5:peek-absent`) |
| `ReadIsPeek` | `read` answers absent instead of the initial value | L5 (`L5:read-gives-initial`) |
| `MaterializingRead` | `read` stores the default it hands out | L5 (`L5:read-pure`) |
| `LazyMaterialize` | `materialize` hands back the initial value and keeps nothing | L5 (`L5:materialize-creates`) |
| `UnloggedWrite` | `write` registers its undo in a transaction nobody holds | L5 (`L5:write-undone-on-abort`) |
| `StringOnlyStorage` | storage keeps only plain strings, so a component codec's `TagNode` is lost | L6 (`L6:component-codec-round-trip`) |
| `SwallowedDecodeFailure` | catches the failure of an undecodable stored form and lets the crossing go on | L6 (`L6:undecodable-load-refused`) |
| `IgnoresDependencies` | plans the slots in id order, not dependencies first | L7 (`L7:dependency-then-id-order`) |
| `UnloggedMaterialize` | `materialize` registers its undo in a transaction nobody holds | L8 (`L8:aborted-write-restores`) |
| `ForgetsMigration` | plans a MIGRATE policy as a keep, so the function never runs | L8 (`L8:committed-crossing-applies`) |
| `UndoOnlyOnSuccess` | hands its undo to the caller's transaction only after every step worked, so a crossing that fails part way leaves what it did | L8 (`L8:failed-crossing-restores`) |
| `SwallowsFailures` | catches whatever goes wrong while applying and carries on | L8 (`L8:failure-reaches-the-caller`) |
| `RetractIsNoop` | the undo of a begin does nothing, so an aborted login leaves the host live | L8 (`L8:undecodable-load-aborts`) |
| `NoOpEngine` | plans nothing: no host begins or ends, nothing is dropped or saved | L4 (`L4:incarnation-ends`); it fails L6 and L2 as well |

`NoOpEngine` breaks no safety law by acting wrongly; it is in the matrix because this is a model contract and a type that
does nothing must still fail one (`../../CONTRACT-PACKAGE.md`). `ForgetfulStorage`, `RotatingEngine` and the declaration
labels beyond totality were added to the seven the decision named, so that L6 and L7 are proven and not only stated.

Every law label the harness asserts is the intended label of an entry above, except the three that
`contracts/state-lifecycle/unmutated.json` allows with its reason. The declaration mutants share one class: `LaxCompiler` takes a `Lapse` (one hole in
the check) and repairs the declaration the way a lenient check would read it before handing it to the reference check, so
every other problem is still reported; `OverStrictCompiler` and `TerseDiagnostics` are the two ways a check can be wrong
without being lenient. The other mutants are plain engines (`Crossings`) and stores (`MemoryHostStore` subclasses), each
named after the mistake.

## 4. Using it

A small player slot set, written the way a mod would. The slots are the mod's; the crossings are performed by one lifecycle
bridge per loader, which turns the loader's events into `Crossing`s.

```java
Transitions transitions = Transitions.builder()
        .declare("mymod:drift", Shape.STAYS, HostKind.PLAYER)
        .build();
TransitionId drift = new TransitionId("mymod:drift");

// A session map that must end at logout. It is dropped at every end and at a respawn, kept elsewhere.
Slot<Map<String, String>> holds = Slot.<Map<String, String>>session("mymod:holds", HostKind.PLAYER, Map.of())
        .on(transitions.shaped(Shape.SAVES, Shape.STAYS, Shape.BEGINS), Policy.keep())
        .on(transitions.shaped(Shape.ENDS, Shape.SUCCEEDS, Shape.FORKS), Policy.drop())
        .build();

// A durable record: saved with the player, copied to a clone, carried through a respawn. Its codec comes from an
// item.component@1 RecordCodec, so unknown fields survive.
Slot<Charm> charm = Slot.durable("mymod:charm", HostKind.PLAYER, Charm.NONE, ComponentSlotCodec.of(Charm.CODEC))
        .on(transitions.shaped(Shape.SAVES, Shape.STAYS, Shape.BEGINS, Shape.ENDS, Shape.SUCCEEDS), Policy.keep())
        .on(transitions.shaped(Shape.FORKS), Policy.copy())
        .build();

// An exclusive token: on one player at a time. A clone takes it; it is never on both.
Slot<String> key = Slot.durable("mymod:key", HostKind.PLAYER, "key", ComponentSlotCodec.of(ComponentCodec.STRING))
        .on(transitions.shaped(Shape.SAVES, Shape.STAYS, Shape.BEGINS, Shape.ENDS, Shape.SUCCEEDS), Policy.keep())
        .on(transitions.shaped(Shape.FORKS), Policy.exclusive())
        .build();

SlotRegistry registry = SlotRegistry.of(transitions, List.of(holds, charm, key)); // throws with every diagnostic, naming slot and transition

// A bridge turns a loader event into a crossing: plan it, apply it in a Tx.
void onLogout(HostRef player) {
    try (Tx tx = Tx.open()) {
        Plan<ServerPlayer> plan = engine.plan(TransitionId.LOGOUT, new Crossing.End<>(player), store, registry);
        engine.apply(plan, store, tx);
        tx.commit();
    }
}
```

A delayed task that needs the player holds a `HostRef`, never the object, and asks the store when it runs:

```java
HostRef who = store.current(playerId).orElseThrow();
schedule(20, () -> store.resolve(who).ifPresent(p -> p.sendMessage(whisper)));
```

A relog, a respawn or a reload between scheduling and running makes `resolve` give nothing, so the whisper never reaches the
new player object. A write through a stale ref, `store.write(slot, who, value, tx)`, answers `Gone`.

What an adapter must do:

- Fire a `Crossing` for every platform event, including the ones a mod never subscribed to (respawn, clone, dimension change).
  A slot's policy at a transition nobody fires is never applied.
- Fire `mycelium:save` before `mycelium:logout`, unload, remove and server stop for any host whose durable slots changed: an
  end releases the live copy and does not write it. Fire `mycelium:server_stop` for every live host.
- Implement `HostStore` over the platform's host objects and the storage port, keeping L2 and L5, and give each durable slot a
  codec whose stored form is immutable with value equality.
- Treat a `CrossingException` from `plan` or `apply` as a failed crossing: abort the `Tx`. A stored form that does not decode
  is one; the stored form is left as it was, and the adapter decides whether to retry or quarantine.
- Relate the traits of `persistence.player-state@1`: its `RESPAWN_SAFE` is a claim about a storage medium, and here a policy
  at `mycelium:respawn` is the statement the engine carries out.

## 5. Running the harness

From the Mycelium root: `bash run-contracts.sh state-lifecycle` (or `.\run-contracts.ps1 state-lifecycle`), or
`./gradlew myceliumVerify -Pmycelium.contracts=state.lifecycle@1`.

Expected result: reference implementation clean; every entry of the rejection matrix `CAUGHT` for its intended label; exit code 0.

## 6. Explicit non-claims

- No Minecraft or mod loader code was executed to produce this package. A passing harness proves the reference engine and
  the matrix; behaviour on a real game is the consumer's evidence, from its own gametests.
- `MemoryHostStore` is an in-memory stand-in; no native storage medium, no real save file and no real player object was
  exercised. A `HostRef` is not persisted and does not survive a restart.
- `item.component@1`'s codec is used through `ComponentSlotCodec`; the harness round-trips a string component through it and
  nothing larger. Schema migration and quarantine are that contract's, not re-proven here.
- Concurrent crossings of one host, crossings across threads, and entity conversion that ends the source in the same crossing
  (a fork followed by a remove, as two crossings) are not modelled. A loader that replaces an entity's object on a dimension
  change reports it as an unload and a load.
- The Gradle plugin does not run the declaration check at build time; `SlotRegistry.of` runs it when the mod's registry is built.
