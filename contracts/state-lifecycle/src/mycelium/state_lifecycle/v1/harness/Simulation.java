package mycelium.state_lifecycle.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.state_lifecycle.v1.fixtures.FakeHost;
import mycelium.state_lifecycle.v1.fixtures.MemoryHostStore;
import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.Durability;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A seeded random sequence of crossings over a handful of hosts: logins, logouts, deaths, respawns, clones, reloads,
 * saves, consumer transitions, stops and restarts, plain accesses, and crossings that are aborted or fail half way. After
 * every step the laws that hold at every moment are checked: L2 over every ref ever issued, L3 over every EXCLUSIVE slot,
 * L4 over every incarnation that ended; L8 on each aborted crossing; L5 on each plain access.
 *
 * <p>A broken engine or store is allowed to derail the sequence, so the next step is chosen from what the store says, never
 * from what the sequence expected. Each failure is reported once.
 */
final class Simulation {
    enum Mode { NORMAL, ABORT, FAIL }

    private final Check c;
    private final Rig rig;
    private final Seeded rnd;
    private final TestSlots slots = new TestSlots();
    private final SlotRegistry registry = slots.registry();
    private final Crossings engine;
    private MemoryHostStore<FakeHost> store;
    private final List<HostRef> issued = new ArrayList<>();
    private final List<HostId> known = new ArrayList<>();
    private final Set<String> reported = new HashSet<>();
    private int spawned;
    int tokenSightings;
    int abortedCrossings;
    int steps;

    Simulation(Check c, Rig rig, long seed) {
        this.c = c;
        this.rig = rig;
        this.rnd = new Seeded(seed);
        this.engine = rig.engine().get();
        this.store = rig.store().get();
        for (String p : List.of("p1", "p2", "p3")) {
            known.add(new HostId(HostKind.PLAYER, "w", p));
        }
        for (String e : List.of("e1", "e2")) {
            known.add(new HostId(HostKind.ENTITY, "w", e));
        }
        known.add(new HostId(HostKind.LEVEL, "w", "lv"));
        known.add(new HostId(HostKind.SERVER, "w", "sv"));
    }

    void run(int count) {
        for (HostId id : known) {
            begin(id, Mode.NORMAL);
        }
        placeTokens();
        for (int i = 0; i < count; i++) {
            step();
            steps++;
            invariants();
        }
    }

    // --- the sequence -----------------------------------------------------------------------------------------

    private void placeTokens() {
        for (HostRef ref : store.live()) {
            Tx tx = Tx.open();
            if (ref.host().kind() == HostKind.PLAYER && !holderExists(slots.token.id())) {
                store.materialize(slots.token, ref, tx);
            }
            if (ref.host().kind() == HostKind.ENTITY && !holderExists(slots.entityToken.id())) {
                store.materialize(slots.entityToken, ref, tx);
            }
            tx.commit();
        }
    }

    private boolean holderExists(SlotId slot) {
        for (HostRef r : store.live()) {
            if (store.cell(r, slot).isPresent()) {
                return true;
            }
        }
        return !store.storedHosts(slot).isEmpty();
    }

    private Mode pickMode() {
        int roll = rnd.nextInt(100);
        return roll < 8 ? Mode.ABORT : roll < 14 ? Mode.FAIL : Mode.NORMAL;
    }

    private void step() {
        HostId id = known.get(rnd.nextInt(known.size()));
        Mode mode = pickMode();
        Optional<HostRef> current = store.current(id);
        if (!current.isPresent()) {
            begin(id, mode);
        } else {
            live(current.get(), mode);
        }
    }

    private void begin(HostId id, Mode mode) {
        TransitionId t = id.kind() == HostKind.PLAYER ? TransitionId.LOGIN : TransitionId.LOAD;
        cross(t, new Crossing.Begin<>(id, new FakeHost(id.id())), mode);
    }

    private void live(HostRef ref, Mode mode) {
        HostKind kind = ref.host().kind();
        boolean pe = kind == HostKind.PLAYER || kind == HostKind.ENTITY;
        int roll = rnd.nextInt(100);
        if (roll < 12) {
            cross(TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
        } else if (roll < 20) {
            cross(pe ? TransitionId.DEATH : TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
        } else if (roll < 26) {
            cross(pe ? TransitionId.DIMENSION_CHANGE : TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
        } else if (roll < 33) {
            cross(pe ? TestSlots.DRIFT : TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
        } else if (roll < 41) {
            if (kind == HostKind.PLAYER) {
                cross(TransitionId.RESPAWN, new Crossing.Succeed<>(ref, new FakeHost(ref.host().id() + "'")), mode);
            } else {
                cross(TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
            }
        } else if (roll < 53) {
            if (pe) {
                HostId target = new HostId(kind, "w", ref.host().id() + "c" + (spawned++));
                known.add(target);
                cross(TransitionId.CLONE, new Crossing.Fork<>(ref, target, new FakeHost(target.id())), mode);
            } else {
                cross(TransitionId.SAVE, new Crossing.Stay<>(ref), mode);
            }
        } else if (roll < 65) {
            end(ref, mode);
        } else if (roll < 75) {
            access(ref);
        } else if (roll < 90) {
            write(ref, mode);
        } else if (roll < 94) {
            stopAndRestart();
        } else {
            staleChecks();
        }
    }

    private void end(HostRef ref, Mode mode) {
        HostKind kind = ref.host().kind();
        if (kind == HostKind.PLAYER) {
            if (rnd.nextBoolean()) {
                cross(TransitionId.SAVE, new Crossing.Stay<>(ref), Mode.NORMAL);
            }
            cross(rnd.nextInt(5) == 0 ? TransitionId.SERVER_STOP : TransitionId.LOGOUT, new Crossing.End<>(ref), mode);
        } else if (kind == HostKind.ENTITY) {
            if (rnd.nextBoolean()) {
                cross(TransitionId.SAVE, new Crossing.Stay<>(ref), Mode.NORMAL);
            }
            cross(rnd.nextBoolean() ? TransitionId.UNLOAD : TransitionId.REMOVE, new Crossing.End<>(ref), mode);
        } else if (kind == HostKind.LEVEL) {
            cross(TransitionId.UNLOAD, new Crossing.End<>(ref), mode);
        } else {
            cross(TransitionId.SERVER_STOP, new Crossing.End<>(ref), mode);
        }
    }

    private void stopAndRestart() {
        if (rnd.nextInt(10) < 7) {
            for (HostRef ref : store.live()) {
                cross(TransitionId.SAVE, new Crossing.Stay<>(ref), Mode.NORMAL);
            }
        }
        for (HostRef ref : store.live()) {
            cross(TransitionId.SERVER_STOP, new Crossing.End<>(ref), Mode.NORMAL);
        }
        expect(store.live().isEmpty(), "L4:incarnation-ends");
        MemoryHostStore<FakeHost> fresh = rig.store().get();
        fresh.adoptStorage(store);
        store = fresh;
        issued.clear();
    }

    // --- accesses ---------------------------------------------------------------------------------------------

    private void access(HostRef ref) {
        List<Slot<?>> inScope = registry.slotsFor(ref.host().kind());
        if (inScope.isEmpty()) {
            return;
        }
        Slot<?> slot = inScope.get(rnd.nextInt(inScope.size()));
        String before = store.dump();
        if (rnd.nextBoolean()) {
            peek(slot, ref);
            expect(before.equals(store.dump()), "L5:peek-pure");
        } else {
            read(slot, ref);
            expect(before.equals(store.dump()), "L5:read-pure");
        }
    }

    private <T> void peek(Slot<T> slot, HostRef ref) {
        store.peek(slot, ref);
    }

    private <T> void read(Slot<T> slot, HostRef ref) {
        store.read(slot, ref);
    }

    private <T> void materialize(Slot<T> slot, HostRef ref, Tx tx) {
        store.materialize(slot, ref, tx);
    }

    private void write(HostRef ref, Mode mode) {
        HostKind kind = ref.host().kind();
        String before = store.dump();
        Tx tx = Tx.open();
        List<Slot<String>> strings = kind == HostKind.PLAYER ? List.of(slots.sessionMap, slots.record, slots.token)
                : kind == HostKind.ENTITY ? List.of(slots.entityMark, slots.entityToken, slots.entityRecord)
                : List.<Slot<String>>of();
        if (strings.isEmpty()) {
            List<Slot<?>> inScope = registry.slotsFor(kind);
            materialize(inScope.get(0), ref, tx);
        } else {
            Slot<String> slot = strings.get(rnd.nextInt(strings.size()));
            if (rnd.nextBoolean()) {
                store.write(slot, ref, "v" + rnd.nextInt(5), tx);
            } else {
                store.materialize(slot, ref, tx);
            }
        }
        if (mode == Mode.ABORT) {
            tx.abort();
            expect(before.equals(store.dump()), "L8:aborted-write-restores");
        } else {
            tx.commit();
        }
    }

    /** A ref that is not current reaches nothing: reads give Gone, and a plan aimed at it is refused. */
    private void staleChecks() {
        List<HostRef> stale = new ArrayList<>();
        for (HostRef r : issued) {
            if (!store.current(r.host()).equals(Optional.of(r))) {
                stale.add(r);
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        HostRef ref = stale.get(rnd.nextInt(stale.size()));
        List<Slot<?>> inScope = registry.slotsFor(ref.host().kind());
        if (!inScope.isEmpty()) {
            expect(peekGone(inScope.get(0), ref), "L2:stale-access-gone");
        }
        if (ref.host().kind() == HostKind.PLAYER || ref.host().kind() == HostKind.ENTITY) {
            String before = store.dump();
            boolean refused;
            try {
                engine.plan(TransitionId.DEATH, new Crossing.Stay<FakeHost>(ref), store, registry);
                refused = false;
            } catch (mycelium.state_lifecycle.v1.model.StaleHostException e) {
                refused = true;
            }
            expect(refused, "L2:stale-plan-refused");
            expect(before.equals(store.dump()), "L2:stale-plan-refused");
        }
    }

    private <T> boolean peekGone(Slot<T> slot, HostRef ref) {
        return store.peek(slot, ref).isGone();
    }

    // --- one crossing -----------------------------------------------------------------------------------------

    private void cross(TransitionId t, Crossing<FakeHost> x, Mode mode) {
        HostRef expectedNew = null;
        if (x instanceof Crossing.Begin<FakeHost> b) {
            expectedNew = new HostRef(b.host(), store.next(b.host()));
        } else if (x instanceof Crossing.Succeed<FakeHost> s) {
            expectedNew = new HostRef(s.host().host(), store.next(s.host().host()));
        } else if (x instanceof Crossing.Fork<FakeHost> f) {
            expectedNew = new HostRef(f.target(), store.next(f.target()));
        }
        String before = store.dump();
        Plan<FakeHost> plan;
        try {
            plan = engine.plan(t, x, store, registry);
        } catch (RuntimeException e) {
            expect(false, "sim:plan-threw " + t + " " + e.getMessage());
            return;
        }
        Tx tx = Tx.open();
        boolean failed = false;
        if (mode == Mode.FAIL) {
            slots.armed.set(true);
        }
        try {
            engine.apply(plan, store, tx);
        } catch (RuntimeException e) {
            failed = true;
            if (mode != Mode.FAIL) {
                expect(false, "sim:apply-threw " + t + " " + e.getMessage());
            }
        } finally {
            slots.armed.set(false);
        }
        if (failed || mode == Mode.ABORT) {
            tx.abort();
            abortedCrossings++;
            expect(before.equals(store.dump()), "L8:aborted-crossing-restores");
            return;
        }
        tx.commit();
        if (x instanceof Crossing.End<FakeHost> e) {
            expect(!store.current(e.host().host()).isPresent(), "L4:incarnation-ends");
        }
        if (expectedNew != null) {
            boolean landed = store.current(expectedNew.host()).equals(Optional.of(expectedNew));
            expect(landed, "L2:incarnation-advances");
            if (landed) {
                issued.add(expectedNew);
            }
        }
        if (x instanceof Crossing.Succeed<FakeHost> s) {
            expect(!store.resolve(s.host()).isPresent(), "L2:stale-ref-gone");
        }
    }

    // --- what holds at every moment ---------------------------------------------------------------------------

    private void invariants() {
        for (HostRef ref : issued) {
            boolean current = store.current(ref.host()).equals(Optional.of(ref));
            expect(store.resolve(ref).isPresent() == current, "L2:stale-ref-gone");
            if (!current) {
                for (SlotId slot : store.cellSlots(ref)) {
                    expect(registry.slot(slot).durability() != Durability.SESSION, "L4:session-slots-empty");
                }
            }
        }
        for (Slot<?> slot : registry.slots()) {
            if (!slot.exclusive()) {
                continue;
            }
            Set<mycelium.state_lifecycle.v1.model.HostId> holders = new HashSet<>();
            for (HostRef r : store.live()) {
                if (store.cell(r, slot.id()).isPresent()) {
                    holders.add(r.host());
                }
            }
            holders.addAll(store.storedHosts(slot.id()));
            expect(holders.size() <= 1, "L3:exclusive-at-most-one");
            if (holders.size() == 1) {
                tokenSightings++;
            }
        }
    }

    private void expect(boolean condition, String label) {
        if (condition) {
            c.that(true, label);
        } else if (reported.add(label)) {
            c.fail(label);
        }
    }
}
