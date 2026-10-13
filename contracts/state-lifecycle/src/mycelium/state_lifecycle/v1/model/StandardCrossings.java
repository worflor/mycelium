package mycelium.state_lifecycle.v1.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The contract's engine. A plan visits the slots of the host's kind in registry order (dependencies first, then id), one
 * group of effects per slot, bracketed by the host's own effect where its incarnation changes. What each policy does on
 * each shape:
 *
 * <pre>
 * SAVES     KEEP: durable PERSIST, session KEEP
 * STAYS     KEEP, DROP, RESET, MIGRATE
 * BEGINS    BEGIN host; then KEEP: durable LOAD (session KEEP), DROP, RESET, MIGRATE: LOAD then MIGRATE
 * ENDS      session DROP; durable KEEP: RELEASE, DROP; then END host
 * SUCCEEDS  END the old incarnation and BEGIN the new; then KEEP: CARRY, DROP (old), RESET: RELEASE old and
 *           RESET new, MIGRATE: CARRY then MIGRATE
 * FORKS     BEGIN the target; then DROP, RESET, MIGRATE (of the source's value), COPY, REGENERATE, EXCLUSIVE: MOVE
 * </pre>
 *
 * <p>A slot that declares a release ({@link Slot.Builder#onRelease}) has it run for each value that ceases: the cell a DROP, a RELEASE or a RESET
 * removes or replaces, once, after the outermost transaction commits (law L9). CARRY, COPY, MOVE, MIGRATE and KEEP move or keep a value and release nothing.
 */
public final class StandardCrossings implements Crossings {
    public static final StandardCrossings INSTANCE = new StandardCrossings();

    private StandardCrossings() {
    }

    // --- plan -------------------------------------------------------------------------------------------------

    @Override
    public <H> Plan<H> plan(TransitionId id, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry) {
        Transition t = registry.transitions().get(id);
        List<Effect> out = new ArrayList<>();
        if (crossing instanceof Crossing.Stay<H> stay) {
            requireShape(t, "Stay", Shape.SAVES, Shape.STAYS);
            requireKind(t, stay.host().host().kind());
            requireCurrent(store, stay.host());
            for (Slot<?> slot : registry.slotsFor(stay.host().host().kind())) {
                Policy.Kind kind = policyOf(slot, t).kind();
                HostRef ref = stay.host();
                if (t.shape() == Shape.SAVES) {
                    out.add(Effect.OfSlot.on(slot, slot.durability() == Durability.DURABLE
                            ? Effect.OfSlot.Op.PERSIST : Effect.OfSlot.Op.KEEP, ref));
                } else {
                    out.add(Effect.OfSlot.on(slot, simple(kind), ref));
                }
            }
        } else if (crossing instanceof Crossing.Begin<H> begin) {
            requireShape(t, "Begin", Shape.BEGINS);
            requireKind(t, begin.host().kind());
            if (store.current(begin.host()).isPresent()) {
                throw new CrossingException(begin.host() + " is already live: it cannot begin again");
            }
            HostRef ref = new HostRef(begin.host(), store.next(begin.host()));
            out.add(new Effect.OfHost(Effect.OfHost.Kind.BEGIN, ref, begin.handle()));
            for (Slot<?> slot : registry.slotsFor(begin.host().kind())) {
                switch (policyOf(slot, t).kind()) {
                    case KEEP:
                        out.add(Effect.OfSlot.on(slot, slot.durability() == Durability.DURABLE
                                ? Effect.OfSlot.Op.LOAD : Effect.OfSlot.Op.KEEP, ref));
                        break;
                    case MIGRATE:
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.LOAD, ref));
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.MIGRATE, ref));
                        break;
                    default:
                        out.add(Effect.OfSlot.on(slot, simple(policyOf(slot, t).kind()), ref));
                }
            }
        } else if (crossing instanceof Crossing.End<H> end) {
            requireShape(t, "End", Shape.ENDS);
            requireKind(t, end.host().host().kind());
            requireCurrent(store, end.host());
            for (Slot<?> slot : registry.slotsFor(end.host().host().kind())) {
                Policy.Kind kind = policyOf(slot, t).kind();
                out.add(Effect.OfSlot.on(slot, kind == Policy.Kind.KEEP ? Effect.OfSlot.Op.RELEASE : Effect.OfSlot.Op.DROP,
                        end.host()));
            }
            out.add(new Effect.OfHost(Effect.OfHost.Kind.END, end.host(), null));
        } else if (crossing instanceof Crossing.Succeed<H> succeed) {
            requireShape(t, "Succeed", Shape.SUCCEEDS);
            HostRef old = succeed.host();
            requireKind(t, old.host().kind());
            requireCurrent(store, old);
            HostRef fresh = new HostRef(old.host(), store.next(old.host()));
            out.add(new Effect.OfHost(Effect.OfHost.Kind.END, old, null));
            out.add(new Effect.OfHost(Effect.OfHost.Kind.BEGIN, fresh, succeed.handle()));
            for (Slot<?> slot : registry.slotsFor(old.host().kind())) {
                switch (policyOf(slot, t).kind()) {
                    case KEEP:
                        out.add(new Effect.OfSlot(slot, Effect.OfSlot.Op.CARRY, old, fresh));
                        break;
                    case DROP:
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.DROP, old));
                        break;
                    case RESET:
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.RELEASE, old));
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.RESET, fresh));
                        break;
                    case MIGRATE:
                        out.add(new Effect.OfSlot(slot, Effect.OfSlot.Op.CARRY, old, fresh));
                        out.add(Effect.OfSlot.on(slot, Effect.OfSlot.Op.MIGRATE, fresh));
                        break;
                    default:
                        throw new IllegalStateException("unreachable: " + policyOf(slot, t));
                }
            }
        } else if (crossing instanceof Crossing.Fork<H> fork) {
            requireShape(t, "Fork", Shape.FORKS);
            HostRef source = fork.source();
            requireKind(t, source.host().kind());
            requireCurrent(store, source);
            if (fork.target().kind() != source.host().kind()) {
                throw new CrossingException("a fork makes a host of the same kind: " + source.host() + " to " + fork.target());
            }
            if (fork.target().equals(source.host()) || store.current(fork.target()).isPresent()) {
                throw new CrossingException(fork.target() + " already exists: a fork makes a new host");
            }
            HostRef target = new HostRef(fork.target(), store.next(fork.target()));
            out.add(new Effect.OfHost(Effect.OfHost.Kind.BEGIN, target, fork.handle()));
            for (Slot<?> slot : registry.slotsFor(source.host().kind())) {
                Effect.OfSlot.Op op;
                switch (policyOf(slot, t).kind()) {
                    case DROP:
                        op = Effect.OfSlot.Op.DROP;
                        break;
                    case RESET:
                        op = Effect.OfSlot.Op.RESET;
                        break;
                    case MIGRATE:
                        op = Effect.OfSlot.Op.MIGRATE;
                        break;
                    case COPY:
                        op = Effect.OfSlot.Op.COPY;
                        break;
                    case REGENERATE:
                        op = Effect.OfSlot.Op.REGENERATE;
                        break;
                    case EXCLUSIVE:
                        op = Effect.OfSlot.Op.MOVE;
                        break;
                    default:
                        throw new IllegalStateException("unreachable: " + policyOf(slot, t));
                }
                out.add(new Effect.OfSlot(slot, op, source, target));
            }
        } else {
            throw new IllegalStateException("unreachable crossing " + crossing);
        }
        return new Plan<>(id, out);
    }

    private static Effect.OfSlot.Op simple(Policy.Kind kind) {
        switch (kind) {
            case KEEP:
                return Effect.OfSlot.Op.KEEP;
            case DROP:
                return Effect.OfSlot.Op.DROP;
            case RESET:
                return Effect.OfSlot.Op.RESET;
            case MIGRATE:
                return Effect.OfSlot.Op.MIGRATE;
            default:
                throw new IllegalStateException("no single effect for " + kind);
        }
    }

    private static Policy<?> policyOf(Slot<?> slot, Transition t) {
        Optional<? extends Policy<?>> p = slot.policy(t.id());
        if (!p.isPresent()) {
            throw new CrossingException("slot " + slot.id() + " has no policy for " + t.id() + ": the registry was not compiled");
        }
        return p.get();
    }

    private static void requireShape(Transition t, String crossing, Shape... shapes) {
        for (Shape s : shapes) {
            if (t.shape() == s) {
                return;
            }
        }
        throw new CrossingException("transition " + t.id() + " is " + t.shape() + ", which a " + crossing + " crossing cannot carry");
    }

    private static void requireKind(Transition t, HostKind kind) {
        if (!t.admits(kind)) {
            throw new CrossingException("transition " + t.id() + " does not apply to " + kind + " hosts");
        }
    }

    private static void requireCurrent(HostStore<?> store, HostRef ref) {
        if (!store.current(ref.host()).equals(Optional.of(ref))) {
            throw new StaleHostException(ref, "the host's current incarnation is " + store.current(ref.host()));
        }
    }

    // --- apply ------------------------------------------------------------------------------------------------

    @Override
    public <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx) {
        checkFresh(plan, store);
        List<Runnable> releases = new ArrayList<>();
        for (Effect effect : plan.effects()) {
            if (effect instanceof Effect.OfHost host) {
                applyHost(host, store, tx);
            } else {
                applySlot(plan.transition(), (Effect.OfSlot) effect, store, tx, releases);
            }
        }
        if (!releases.isEmpty()) {
            // after the outermost commit, never before: a crossing that aborts has released nothing, because it has lost nothing
            tx.onOuterCommit(() -> runReleases(releases));
        }
    }

    /** Runs every release, in plan order; if some throw, the rest still run and the first failure is thrown after them. */
    private static void runReleases(List<Runnable> releases) {
        RuntimeException first = null;
        for (Runnable release : releases) {
            try {
                release.run();
            } catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw new CrossingException("a release failed (the crossing had committed, and every other release ran): " + first.getMessage(), first);
        }
    }

    /** Every incarnation the plan names is what the plan expects, or nothing is applied. */
    private static <H> void checkFresh(Plan<H> plan, HostStore<H> store) {
        Set<HostRef> begun = new HashSet<>();
        for (Effect e : plan.effects()) {
            if (e instanceof Effect.OfHost h && h.kind() == Effect.OfHost.Kind.BEGIN) {
                begun.add(h.ref());
            }
        }
        Set<HostRef> ended = new HashSet<>();
        for (Effect e : plan.effects()) {
            if (e instanceof Effect.OfHost h && h.kind() == Effect.OfHost.Kind.END) {
                ended.add(h.ref());
            }
        }
        for (HostRef ref : begun) {
            // a succession begins the new incarnation while the old one is live; the same plan ends it
            Optional<HostRef> live = store.current(ref.host());
            boolean free = !live.isPresent() || ended.contains(live.get());
            if (!free || !store.next(ref.host()).equals(ref.incarnation())) {
                throw new StaleHostException(ref, "the host changed since the plan was made");
            }
        }
        for (Effect e : plan.effects()) {
            if (e instanceof Effect.OfHost h) {
                requireLive(begun, store, h.ref());
            } else {
                Effect.OfSlot s = (Effect.OfSlot) e;
                requireLive(begun, store, s.from());
                requireLive(begun, store, s.to());
            }
        }
    }

    private static void requireLive(Set<HostRef> begun, HostStore<?> store, HostRef ref) {
        if (!begun.contains(ref)) {
            requireCurrent(store, ref);
        }
    }

    @SuppressWarnings("unchecked")
    private static <H> void applyHost(Effect.OfHost e, HostStore<H> store, Tx tx) {
        HostRef ref = e.ref();
        if (e.kind() == Effect.OfHost.Kind.BEGIN) {
            store.begin(ref, (H) e.handle());
            tx.registerUndo(() -> store.retract(ref));
        } else {
            H handle = store.resolve(ref).orElseThrow(() -> new StaleHostException(ref, "no object to end"));
            store.end(ref);
            tx.registerUndo(() -> store.restore(ref, handle));
        }
    }

    private static void applySlot(TransitionId at, Effect.OfSlot e, HostStore<?> store, Tx tx, List<Runnable> releases) {
        if (e.op() == Effect.OfSlot.Op.KEEP) {
            return;
        }
        Slot<?> slot = e.slot();
        SlotId id = slot.id();
        HostRef from = e.from();
        HostRef to = e.to();
        Optional<Object> cellFrom = store.cell(from, id);
        Optional<Object> cellTo = store.cell(to, id);
        Optional<Object> storedFrom = store.stored(from.host(), id);
        Optional<Object> storedTo = store.stored(to.host(), id);
        tx.registerUndo(() -> {
            putOrRemoveCell(store, to, id, cellTo);
            putOrRemoveCell(store, from, id, cellFrom);
            putOrRemoveStored(store, to.host(), id, storedTo);
            putOrRemoveStored(store, from.host(), id, storedFrom);
        });
        boolean ceases = e.op() == Effect.OfSlot.Op.DROP || e.op() == Effect.OfSlot.Op.RELEASE || e.op() == Effect.OfSlot.Op.RESET;
        if (ceases && slot.releases() && cellTo.isPresent()) {
            Object value = cellTo.get();
            releases.add(() -> slot.releaseValue(value)); // the value that was there, which the DROP or RESET is about to replace
        }
        switch (e.op()) {
            case DROP:
                store.removeCell(to, id);
                if (slot.durability() == Durability.DURABLE) {
                    store.removeStored(to.host(), id);
                }
                break;
            case RELEASE:
                store.removeCell(to, id);
                break;
            case RESET:
                store.putCell(to, id, slot.initial());
                break;
            case MIGRATE:
                if (cellFrom.isPresent()) {
                    store.putCell(to, id, nonNull(slot, slot.migrateValue(at, cellFrom.get())));
                }
                break;
            case LOAD:
                if (storedTo.isPresent()) {
                    try {
                        store.putCell(to, id, slot.decodeValue(storedTo.get()));
                    } catch (SlotCodecException ex) {
                        throw new CrossingException("the stored value of " + id + " for " + to.host()
                                + " does not decode: " + ex.getMessage(), ex);
                    }
                }
                break;
            case PERSIST:
                if (cellTo.isPresent()) {
                    store.putStored(to.host(), id, slot.encodeValue(cellTo.get()));
                } else {
                    store.removeStored(to.host(), id);
                }
                break;
            case CARRY:
                if (cellFrom.isPresent()) {
                    store.putCell(to, id, cellFrom.get());
                    if (!from.equals(to)) {
                        store.removeCell(from, id);
                    }
                }
                break;
            case COPY:
                if (cellFrom.isPresent()) {
                    store.putCell(to, id, cellFrom.get());
                }
                break;
            case REGENERATE:
                store.putCell(to, id, nonNull(slot, slot.regenerateValue(at, to.host())));
                break;
            case MOVE:
                if (cellFrom.isPresent()) {
                    store.putCell(to, id, cellFrom.get());
                    store.removeCell(from, id);
                }
                if (slot.durability() == Durability.DURABLE && storedFrom.isPresent()) {
                    store.putStored(to.host(), id, storedFrom.get());
                    store.removeStored(from.host(), id);
                }
                break;
            default:
                throw new IllegalStateException("unreachable: " + e.op());
        }
    }

    private static Object nonNull(Slot<?> slot, Object value) {
        if (value == null) {
            throw new CrossingException("a policy of " + slot.id() + " produced null: a slot never holds null");
        }
        return value;
    }

    private static void putOrRemoveCell(HostStore<?> store, HostRef ref, SlotId id, Optional<Object> value) {
        if (value.isPresent()) {
            store.putCell(ref, id, value.get());
        } else {
            store.removeCell(ref, id);
        }
    }

    private static void putOrRemoveStored(HostStore<?> store, HostId host, SlotId id, Optional<Object> value) {
        if (value.isPresent()) {
            store.putStored(host, id, value.get());
        } else {
            store.removeStored(host, id);
        }
    }
}
