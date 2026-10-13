package mycelium.state_lifecycle.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.state_lifecycle.v1.fixtures.FakeHost;
import mycelium.state_lifecycle.v1.fixtures.MemoryHostStore;
import mycelium.state_lifecycle.v1.model.Compilation;
import mycelium.state_lifecycle.v1.model.ComponentSlotCodec;
import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.CrossingException;
import mycelium.state_lifecycle.v1.model.Diagnostic;
import mycelium.state_lifecycle.v1.model.Durability;
import mycelium.state_lifecycle.v1.model.Effect;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.Policy;
import mycelium.state_lifecycle.v1.model.Reading;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StaleHostException;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Laws L1 to L9 of {@code state.lifecycle@1}, run against whatever the rig supplies. */
public final class StateLifecycleContractTests {
    private final Rig rig;

    StateLifecycleContractTests(Rig rig) {
        this.rig = rig;
    }

    public Check run() {
        Check c = new Check();
        guard(c, "L1", () -> l1Declarations(c));
        guard(c, "L2", () -> l2NoStaleHosts(c));
        guard(c, "L3", () -> l3Exclusivity(c));
        guard(c, "L4", () -> l4SessionsEnd(c));
        guard(c, "L5", () -> l5PeekIsPure(c));
        guard(c, "L6", () -> l6DurableRoundTrip(c));
        guard(c, "L7", () -> l7DeterministicPlans(c));
        guard(c, "L8", () -> l8AtomicCrossings(c));
        guard(c, "L9", () -> l9Releases(c));
        guard(c, "sequences", () -> randomSequences(c));
        return c;
    }

    /** A law's fixed scenario that throws (an engine that cannot even run it) is a failure of that law, and the others still run. */
    private static void guard(Check c, String law, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            c.fail(law + ":scenario-threw " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // --- a world to cross in --------------------------------------------------------------------------------

    private final class World {
        final TestSlots s = new TestSlots();
        final SlotRegistry registry = s.registry();
        final Crossings engine = rig.engine().get();
        MemoryHostStore<FakeHost> store = rig.store().get();

        HostId player(String name) {
            return new HostId(HostKind.PLAYER, "w", name);
        }

        Plan<FakeHost> cross(TransitionId t, Crossing<FakeHost> x) {
            Plan<FakeHost> plan = engine.plan(t, x, store, registry);
            try (Tx tx = Tx.open()) {
                engine.apply(plan, store, tx);
                tx.commit();
            }
            return plan;
        }

        /** Logs a player in and returns the ref the login should have made. */
        HostRef login(String name) {
            HostId id = player(name);
            HostRef expected = new HostRef(id, store.next(id));
            cross(TransitionId.LOGIN, new Crossing.Begin<>(id, new FakeHost(name)));
            return expected;
        }

        void logout(HostRef ref) {
            cross(TransitionId.LOGOUT, new Crossing.End<>(ref));
        }

        void save(HostRef ref) {
            cross(TransitionId.SAVE, new Crossing.Stay<>(ref));
        }

        <T> void put(Slot<T> slot, HostRef ref, T value) {
            try (Tx tx = Tx.open()) {
                store.write(slot, ref, value, tx);
                tx.commit();
            }
        }

        /** A server restart: the stored forms stay, everything live is gone. */
        void restart() {
            MemoryHostStore<FakeHost> fresh = rig.store().get();
            fresh.adoptStorage(store);
            store = fresh;
        }
    }

    // --- L1: declarations -----------------------------------------------------------------------------------

    private List<Diagnostic> rejected(TestSlots ts, Slot<?>... slots) {
        Compilation result = rig.compiler().compile(ts.transitions, List.of(slots));
        return result instanceof Compilation.Rejected r ? r.diagnostics() : List.<Diagnostic>of();
    }

    private static boolean names(List<Diagnostic> diagnostics, Diagnostic.Code code, String slot, String transition) {
        for (Diagnostic d : diagnostics) {
            boolean slotOk = slot == null || (d.slot() != null && d.slot().value().equals(slot));
            boolean transitionOk = transition == null || (d.transition() != null && d.transition().value().equals(transition));
            if (d.code() == code && slotOk && transitionOk) {
                return true;
            }
        }
        return false;
    }

    private Slot.Builder<String> sound(TestSlots ts, String id) {
        return ts.shaped(Slot.session(id, HostKind.PLAYER, "i"), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.drop(), Policy.drop());
    }

    private void l1Declarations(Check c) {
        TestSlots ts = new TestSlots();
        Slot<String> ok = sound(ts, "x:ok").build();
        c.that(rig.compiler().compile(ts.transitions, List.of(ok)) instanceof Compilation.Accepted, "L1:complete-accepted");

        Slot<String> gap = ts.shaped(Slot.session("x:gap", HostKind.PLAYER, "i"), HostKind.PLAYER,
                Policy.keep(), null, Policy.keep(), Policy.drop(), Policy.drop(), Policy.drop()).build();
        List<Diagnostic> missing = rejected(ts, gap);
        c.that(names(missing, Diagnostic.Code.MISSING_POLICY, "x:gap", "mycelium:death"), "L1:missing-policy-rejected");
        boolean named = false;
        for (Diagnostic d : missing) {
            named |= d.message().contains("x:gap") && d.message().contains("mycelium:death");
        }
        c.that(named, "L1:diagnostic-names-slot-and-transition");

        Slot<String> stray = sound(ts, "x:stray").on(new TransitionId("x:nowhere"), Policy.keep()).build();
        c.that(names(rejected(ts, stray), Diagnostic.Code.UNKNOWN_TRANSITION, "x:stray", "x:nowhere"), "L1:unknown-transition-rejected");

        Slot<String> outside = sound(ts, "x:outside").on(TransitionId.LOAD, Policy.keep()).build();
        c.that(names(rejected(ts, outside), Diagnostic.Code.OUT_OF_SCOPE_POLICY, "x:outside", "mycelium:load"), "L1:out-of-scope-rejected");

        c.that(names(rejected(ts, sound(ts, "x:copy").on(TransitionId.DEATH, Policy.copy()).build()),
                Diagnostic.Code.INADMISSIBLE_POLICY, "x:copy", "mycelium:death"), "L1:copy-only-on-forks");
        c.that(names(rejected(ts, sound(ts, "x:regen").on(TransitionId.LOGIN, Policy.regenerate(h -> "g")).build()),
                Diagnostic.Code.INADMISSIBLE_POLICY, "x:regen", "mycelium:login"), "L1:regenerate-only-on-forks");
        c.that(names(rejected(ts, sound(ts, "x:excl").on(TransitionId.RESPAWN, Policy.exclusive()).build()),
                Diagnostic.Code.INADMISSIBLE_POLICY, "x:excl", "mycelium:respawn"), "L1:exclusive-only-on-forks");
        c.that(names(rejected(ts, sound(ts, "x:kept").on(TransitionId.LOGOUT, Policy.keep()).build()),
                Diagnostic.Code.INADMISSIBLE_POLICY, "x:kept", "mycelium:logout"), "L1:session-dropped-at-end");

        Slot<String> bare = ts.shaped(Slot.builder(new SlotId("x:bare"), HostKind.PLAYER, Durability.DURABLE, "i"), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.copy()).build();
        c.that(names(rejected(ts, bare), Diagnostic.Code.DURABLE_WITHOUT_CODEC, "x:bare", null), "L1:durable-needs-codec");

        Slot<String> a = sound(ts, "x:a").dependsOn(new SlotId("x:b")).build();
        Slot<String> b = sound(ts, "x:b").dependsOn(new SlotId("x:a")).build();
        List<Diagnostic> cycle = rejected(ts, a, b);
        c.that(names(cycle, Diagnostic.Code.DEPENDENCY_CYCLE, "x:a", null) && names(cycle, Diagnostic.Code.DEPENDENCY_CYCLE, "x:b", null),
                "L1:cycle-rejected");

        c.that(names(rejected(ts, sound(ts, "x:orphan").dependsOn(new SlotId("x:nothing")).build()),
                Diagnostic.Code.UNKNOWN_DEPENDENCY, "x:orphan", null), "L1:unknown-dependency");

        Slot<Integer> level = ts.shaped(Slot.session("x:level", HostKind.LEVEL, 0), HostKind.LEVEL,
                Policy.keep(), null, Policy.reset(), Policy.drop(), null, null).build();
        c.that(names(rejected(ts, sound(ts, "x:far").dependsOn(level.id()).build(), level),
                Diagnostic.Code.CROSS_SCOPE_DEPENDENCY, "x:far", null), "L1:cross-scope-dependency");

        c.that(names(rejected(ts, ok, sound(ts, "x:ok").build()), Diagnostic.Code.DUPLICATE_SLOT, "x:ok", null), "L1:duplicate-slot-rejected");

        Slot<String> greedy = ts.shaped(Slot.durable("x:greedy", HostKind.PLAYER, "i", Codecs.STRING), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.exclusive())
                .on(TransitionId.DEATH, Policy.reset()).build();
        c.that(names(rejected(ts, greedy), Diagnostic.Code.EXCLUSIVE_CREATES_VALUE, "x:greedy", "mycelium:death"),
                "L1:exclusive-creates-value");

        World w = new World();
        HostRef r = w.login("p1");
        Plan<FakeHost> login = w.engine.plan(TransitionId.LOGIN, new Crossing.Begin<>(w.player("p2"), new FakeHost("p2")), w.store, w.registry);
        List<String> planned = new ArrayList<>();
        for (Effect e : login.effects()) {
            if (e instanceof Effect.OfSlot s) {
                planned.add(s.slot().id().value());
            }
        }
        List<String> declared = new ArrayList<>();
        for (Slot<?> s : w.registry.slotsFor(HostKind.PLAYER)) {
            declared.add(s.id().value());
        }
        c.equal(declared, planned, "L1:plan-covers-scope");
        c.that(r.incarnation().value() == 1, "L1:first-incarnation");
    }

    // --- L2: no stale hosts -----------------------------------------------------------------------------------

    private void l2NoStaleHosts(Check c) {
        World w = new World();
        HostRef first = w.login("p1");
        c.that(w.store.resolve(first).isPresent(), "L2:live-ref-resolves");
        w.put(w.s.sessionMap, first, "mine");
        w.logout(first);
        c.that(!w.store.resolve(first).isPresent(), "L2:stale-ref-gone");

        HostRef second = w.login("p1");
        c.that(second.incarnation().value() == 2 && w.store.current(second.host()).equals(Optional.of(second)),
                "L2:incarnation-advances");
        c.that(!w.store.resolve(first).isPresent(), "L2:stale-ref-gone");
        c.that(w.store.resolve(second).isPresent(), "L2:live-ref-resolves");

        Reading<String> stale = w.store.peek(w.s.sessionMap, first);
        c.that(stale.isGone(), "L2:stale-access-gone");
        Tx tx = Tx.open();
        Reading<String> written = w.store.write(w.s.sessionMap, first, "ghost", tx);
        tx.commit();
        c.that(written.isGone() && !w.store.cell(second, w.s.sessionMap.id()).isPresent(), "L2:stale-access-gone");

        // a plan made for the old incarnation is refused after the host came back
        Plan<FakeHost> plan = w.engine.plan(TransitionId.DEATH, new Crossing.Stay<>(second), w.store, w.registry);
        w.logout(second);
        HostRef third = w.login("p1");
        String before = w.store.dump();
        boolean refused = false;
        try (Tx t2 = Tx.open()) {
            w.engine.apply(plan, w.store, t2);
            t2.commit();
        } catch (StaleHostException e) {
            refused = true;
        }
        c.that(refused && before.equals(w.store.dump()), "L2:stale-plan-refused");
        boolean planRefused = false;
        try {
            w.engine.plan(TransitionId.DEATH, new Crossing.Stay<>(second), w.store, w.registry);
        } catch (StaleHostException e) {
            planRefused = true;
        }
        c.that(planRefused, "L2:stale-plan-refused");
        c.that(w.store.resolve(third).isPresent(), "L2:live-ref-resolves");
    }

    // --- L3: exclusivity --------------------------------------------------------------------------------------

    private void l3Exclusivity(Check c) {
        World w = new World();
        HostRef p1 = w.login("p1");
        HostRef p2 = w.login("p2");
        Tx tx = Tx.open();
        c.that(w.store.materialize(w.s.token, p1, tx) instanceof Reading.Present, "L3:first-holder-created");
        Reading<String> second = w.store.materialize(w.s.token, p2, tx);
        tx.commit();
        c.that(second instanceof Reading.Held<String> held && held.holder().equals(p1.host()), "L3:second-holder-refused");

        HostId target = new HostId(HostKind.PLAYER, "w", "p1-double");
        w.cross(TransitionId.CLONE, new Crossing.Fork<>(p1, target, new FakeHost("double")));
        HostRef clone = w.store.current(target).orElseThrow(() -> new IllegalStateException("the clone did not begin"));
        List<HostId> holders = new ArrayList<>();
        for (HostRef r : w.store.live()) {
            if (w.store.cell(r, w.s.token.id()).isPresent()) {
                holders.add(r.host());
            }
        }
        c.that(holders.size() <= 1, "L3:exclusive-at-most-one");
        c.that(holders.equals(List.of(target)), "L3:clone-takes-the-token");
        c.that(w.store.peek(w.s.record, p1).isAbsent() || w.store.peek(w.s.record, p1).valueOrNull() != null, "L3:fixture-reads");

        // a copy-policy slot, on the other hand, is on both
        w.put(w.s.record, p1, "kept");
        w.cross(TransitionId.CLONE, new Crossing.Fork<>(p1, new HostId(HostKind.PLAYER, "w", "p1-again"), new FakeHost("again")));
        HostRef again = w.store.current(new HostId(HostKind.PLAYER, "w", "p1-again")).orElseThrow(IllegalStateException::new);
        c.equal("kept", w.store.peek(w.s.record, again).valueOrNull(), "L3:copy-is-on-both");
        c.equal("kept", w.store.peek(w.s.record, p1).valueOrNull(), "L3:copy-is-on-both");
        c.that(clone.incarnation().value() == 1, "L3:fork-target-starts-at-first-incarnation");
    }

    // --- L4: sessions end -------------------------------------------------------------------------------------

    private void l4SessionsEnd(Check c) {
        World w = new World();
        HostRef p1 = w.login("p1");
        w.put(w.s.sessionMap, p1, "held");
        w.put(w.s.tripwire, p1, 3);
        boolean wasLive = w.store.current(p1.host()).isPresent();
        w.logout(p1);
        c.that(wasLive && !w.store.current(p1.host()).isPresent(), "L4:incarnation-ends");
        c.that(w.store.cellSlots(p1).isEmpty(), "L4:session-slots-empty");

        HostRef p2 = w.login("p2");
        HostId e1 = new HostId(HostKind.ENTITY, "w", "e1");
        w.put(w.s.sessionMap, p2, "held");
        HostRef entity = new HostRef(e1, w.store.next(e1));
        w.cross(TransitionId.LOAD, new Crossing.Begin<>(e1, new FakeHost("e1")));
        w.put(w.s.entityMark, entity, "marked");
        // the server stops: every live host ends, whatever its kind
        for (HostRef live : w.store.live()) {
            w.cross(TransitionId.SERVER_STOP, new Crossing.End<>(live));
        }
        c.that(w.store.live().isEmpty(), "L4:incarnation-ends");
        c.that(w.store.cellSlots(p2).isEmpty() && w.store.cellSlots(entity).isEmpty(), "L4:session-slots-empty");

        // a respawn ends the old incarnation too
        HostRef p3 = w.login("p3");
        w.put(w.s.sessionMap, p3, "held");
        w.put(w.s.tripwire, p3, 1);
        w.cross(TransitionId.RESPAWN, new Crossing.Succeed<>(p3, new FakeHost("p3-again")));
        c.that(w.store.cellSlots(p3).isEmpty(), "L4:session-slots-empty");
        HostRef p3b = w.store.current(p3.host()).orElseThrow(IllegalStateException::new);
        c.that(p3b.incarnation().value() == 2, "L2:incarnation-advances");
        c.equal(Integer.valueOf(2), w.store.peek(w.s.tripwire, p3b).valueOrNull(), "L4:carried-session-value");
    }

    // --- L5: peek is pure ---------------------------------------------------------------------------------------

    private void l5PeekIsPure(Check c) {
        World w = new World();
        HostRef p1 = w.login("p1");
        String before = w.store.dump();
        Reading<String> peeked = w.store.peek(w.s.sessionMap, p1);
        c.that(peeked.isAbsent(), "L5:peek-absent");
        c.that(before.equals(w.store.dump()), "L5:peek-pure");
        Reading<String> read = w.store.read(w.s.sessionMap, p1);
        c.equal("none", read.valueOrNull(), "L5:read-gives-initial");
        c.that(before.equals(w.store.dump()), "L5:read-pure");
        Tx tx = Tx.open();
        Reading<String> made = w.store.materialize(w.s.sessionMap, p1, tx);
        tx.commit();
        c.equal("none", made.valueOrNull(), "L5:materialize-creates");
        c.that(w.store.peek(w.s.sessionMap, p1) instanceof Reading.Present, "L5:materialize-creates");
        Tx back = Tx.open();
        w.store.write(w.s.record, p1, "x", back);
        back.abort();
        c.that(w.store.peek(w.s.record, p1).isAbsent(), "L5:write-undone-on-abort");
    }

    // --- L6: durable round trip ---------------------------------------------------------------------------------

    private void l6DurableRoundTrip(Check c) {
        Seeded rnd = new Seeded(60);
        boolean allEqual = true;
        for (int i = 0; i < 24; i++) {
            World w = new World();
            HostRef p = w.login("p1");
            String text = "r" + Long.toHexString(rnd.nextLong()) + "|;=" + (char) ('a' + rnd.nextInt(26));
            Map<String, Integer> counts = new TreeMap<>();
            for (int k = rnd.nextInt(4); k >= 0; k--) {
                counts.put("k" + rnd.nextInt(9), rnd.nextInt(1000));
            }
            Map<String, Integer> frozen = Collections.unmodifiableMap(counts);
            w.put(w.s.record, p, text);
            w.put(w.s.counts, p, frozen);
            w.save(p);
            w.logout(p);
            if (rnd.nextBoolean()) {
                w.restart();
            }
            HostRef again = w.login("p1");
            boolean sameText = w.store.peek(w.s.record, again) instanceof Reading.Present<String> got
                    && Codecs.STRING.same(text, got.value());
            boolean sameCounts = w.store.peek(w.s.counts, again) instanceof Reading.Present<Map<String, Integer>> got
                    && Codecs.COUNTS.same(frozen, got.value());
            allEqual &= sameText && sameCounts;
        }
        c.that(allEqual, "L6:durable-round-trip");

        // a slot made from an item.component@1 codec rounds trips the same way
        TestSlots ts = new TestSlots();
        Slot<String> viaComponent = ts.shaped(Slot.durable("x:component", HostKind.PLAYER, "", ComponentSlotCodec.of(ComponentCodec.STRING)),
                HostKind.PLAYER, Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.copy()).build();
        SlotRegistry registry = SlotRegistry.of(ts.transitions, List.of(viaComponent));
        Crossings engine = rig.engine().get();
        MemoryHostStore<FakeHost> store = rig.store().get();
        HostId id = new HostId(HostKind.PLAYER, "w", "p1");
        HostRef ref = new HostRef(id, store.next(id));
        apply(engine, store, registry, TransitionId.LOGIN, new Crossing.Begin<>(id, new FakeHost("p1")));
        try (Tx tx = Tx.open()) {
            store.write(viaComponent, ref, "café", tx);
            tx.commit();
        }
        apply(engine, store, registry, TransitionId.SAVE, new Crossing.Stay<>(ref));
        apply(engine, store, registry, TransitionId.LOGOUT, new Crossing.End<>(ref));
        HostRef next = new HostRef(id, store.next(id));
        apply(engine, store, registry, TransitionId.LOGIN, new Crossing.Begin<>(id, new FakeHost("p1")));
        c.equal("café", store.peek(viaComponent, next).valueOrNull(), "L6:component-codec-round-trip");

        // a stored form that does not decode stops the login and changes nothing
        World w = new World();
        HostId q = w.player("q");
        w.store.putStored(q, w.s.record.id(), "garbage");
        String before = w.store.dump();
        boolean stopped = false;
        try (Tx tx = Tx.open()) {
            Plan<FakeHost> plan = w.engine.plan(TransitionId.LOGIN, new Crossing.Begin<>(q, new FakeHost("q")), w.store, w.registry);
            w.engine.apply(plan, w.store, tx);
            tx.commit();
        } catch (CrossingException e) {
            stopped = true;
        }
        c.that(stopped, "L6:undecodable-load-refused");
        c.that(before.equals(w.store.dump()), "L8:undecodable-load-aborts");
    }

    private static void apply(Crossings engine, MemoryHostStore<FakeHost> store, SlotRegistry registry, TransitionId t,
                              Crossing<FakeHost> x) {
        Plan<FakeHost> plan = engine.plan(t, x, store, registry);
        try (Tx tx = Tx.open()) {
            engine.apply(plan, store, tx);
            tx.commit();
        }
    }

    // --- L7: deterministic plans --------------------------------------------------------------------------------

    private void l7DeterministicPlans(Check c) {
        World w = new World();
        HostRef p1 = w.login("p1");
        w.put(w.s.sessionMap, p1, "x");
        Plan<FakeHost> a = w.engine.plan(TransitionId.DEATH, new Crossing.Stay<>(p1), w.store, w.registry);
        Plan<FakeHost> b = w.engine.plan(TransitionId.DEATH, new Crossing.Stay<>(p1), w.store, w.registry);
        c.that(a.equals(b), "L7:same-input-same-plan");
        HostId p2 = w.player("p2");
        FakeHost handle = new FakeHost("p2");
        Plan<FakeHost> x = w.engine.plan(TransitionId.LOGIN, new Crossing.Begin<>(p2, handle), w.store, w.registry);
        Plan<FakeHost> y = w.engine.plan(TransitionId.LOGIN, new Crossing.Begin<>(p2, handle), w.store, w.registry);
        c.that(x.effects().equals(y.effects()), "L7:same-input-same-plan");

        List<Slot<?>> backwards = new ArrayList<>(w.s.all);
        Collections.reverse(backwards);
        SlotRegistry other = SlotRegistry.of(w.s.transitions, backwards);
        Plan<FakeHost> z = w.engine.plan(TransitionId.DEATH, new Crossing.Stay<>(p1), w.store, other);
        c.that(a.effects().equals(z.effects()), "L7:declaration-order-irrelevant");

        List<String> order = new ArrayList<>();
        for (Effect e : x.effects()) {
            if (e instanceof Effect.OfSlot s) {
                order.add(s.slot().id().value());
            }
        }
        c.equal(List.of("t:counts", "t:session_map", "t:record", "t:token", "t:zz_tripwire"), order, "L7:dependency-then-id-order");
    }

    // --- L8: atomic crossings -----------------------------------------------------------------------------------

    private World withValues() {
        World w = new World();
        HostRef p1 = w.login("p1");
        w.put(w.s.sessionMap, p1, "held");
        w.put(w.s.record, p1, "kept");
        w.put(w.s.tripwire, p1, 5);
        w.save(p1);
        return w;
    }

    private void l8AtomicCrossings(Check c) {
        World w = withValues();
        HostRef p1 = w.store.live().get(0);
        for (TransitionId t : List.of(TransitionId.DEATH, TestSlots.DRIFT)) {
            String before = w.store.dump();
            Plan<FakeHost> plan = w.engine.plan(t, new Crossing.Stay<>(p1), w.store, w.registry);
            Tx tx = Tx.open();
            w.engine.apply(plan, w.store, tx);
            tx.abort();
            c.that(before.equals(w.store.dump()), "L8:aborted-crossing-restores");
        }
        w = withValues();
        p1 = w.store.live().get(0);
        String beforeRespawn = w.store.dump();
        Plan<FakeHost> respawn = w.engine.plan(TransitionId.RESPAWN, new Crossing.Succeed<>(p1, new FakeHost("again")), w.store, w.registry);
        Tx tx = Tx.open();
        w.engine.apply(respawn, w.store, tx);
        tx.abort();
        c.that(beforeRespawn.equals(w.store.dump()), "L8:aborted-crossing-restores");
        c.that(w.store.resolve(p1).isPresent(), "L8:aborted-crossing-restores");

        w = withValues();
        p1 = w.store.live().get(0);

        // a crossing that fails part way: the record's migration ran, then the tripwire's threw
        String beforeDrift = w.store.dump();
        Plan<FakeHost> drift = w.engine.plan(TestSlots.DRIFT, new Crossing.Stay<>(p1), w.store, w.registry);
        Tx failing = Tx.open();
        w.s.armed.set(true);
        boolean threw = false;
        try {
            w.engine.apply(drift, w.store, failing);
        } catch (RuntimeException e) {
            threw = true;
        } finally {
            w.s.armed.set(false);
        }
        c.that(threw, "L8:failure-reaches-the-caller");
        c.that(!beforeDrift.equals(w.store.dump()), "L8:failure-was-partial");
        failing.abort();
        c.that(beforeDrift.equals(w.store.dump()), "L8:failed-crossing-restores");

        // the same plan, with nothing failing, commits
        Tx good = Tx.open();
        w.engine.apply(drift, w.store, good);
        good.commit();
        c.equal("kept+", w.store.peek(w.s.record, p1).valueOrNull(), "L8:committed-crossing-applies");
    }

    // --- seeded sequences ---------------------------------------------------------------------------------------

    // --- L9: a value that ceases is released once; one that moves is not ----------------------------------------

    private void l9Releases(Check c) {
        TestSlots ts = new TestSlots();
        List<String> log = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        Slot<String> lamp = ts.shaped(Slot.<String>session("t:r_lamp", HostKind.PLAYER, "dark").onRelease(v -> log.add("lamp:" + v)), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.keep(), Policy.drop())
                .on(TestSlots.DRIFT, Policy.reset()).on(TransitionId.DEATH, Policy.drop()).build();
        Slot<String> bulb = ts.shaped(Slot.<String>session("t:r_bulb", HostKind.PLAYER, "off").onRelease(v -> log.add("bulb:" + v)), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.drop(), Policy.drop()).build();
        Slot<String> wick = ts.shaped(Slot.<String>session("t:r_wick", HostKind.PLAYER, "wick0").onRelease(v -> log.add("wick:" + v)), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.reset(), Policy.drop()).build();
        Slot<String> filament = ts.shaped(Slot.<String>durable("t:r_filament", HostKind.PLAYER, "", Codecs.STRING).onRelease(v -> log.add("filament:" + v)),
                HostKind.PLAYER, Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.copy()).build();
        Policy<Integer> strain = Policy.migrate(n -> {
            if (armed.get()) {
                throw new IllegalStateException("tripwire");
            }
            return n + 1;
        });
        Slot<Integer> trip = ts.shaped(Slot.<Integer>session("t:r_zz_trip", HostKind.PLAYER, 0), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.keep(), Policy.drop()).on(TestSlots.DRIFT, strain).build();
        SlotRegistry registry = SlotRegistry.of(ts.transitions, List.of(lamp, bulb, wick, filament, trip));
        Crossings engine = rig.engine().get();
        MemoryHostStore<FakeHost> store = rig.store().get();
        java.util.function.BiConsumer<TransitionId, Crossing<FakeHost>> cross = (t, x) -> {
            Plan<FakeHost> plan = engine.plan(t, x, store, registry);
            try (Tx tx = Tx.open()) {
                engine.apply(plan, store, tx);
                tx.commit();
            }
        };
        HostId id = new HostId(HostKind.PLAYER, "w", "p9");
        HostRef p = new HostRef(id, store.next(id));
        cross.accept(TransitionId.LOGIN, new Crossing.Begin<>(id, new FakeHost("p9")));
        try (Tx tx = Tx.open()) {
            store.write(lamp, p, "L1", tx);
            store.write(bulb, p, "B1", tx);
            store.write(wick, p, "W1", tx);
            store.write(filament, p, "F1", tx);
            store.write(trip, p, 1, tx);
            tx.commit();
        }
        c.that(log.isEmpty(), "L9:released-once");

        // a RESET releases the value it replaces, once
        cross.accept(TestSlots.DRIFT, new Crossing.Stay<>(p));
        c.equal(List.of("lamp:L1"), new ArrayList<>(log), "L9:released-once");
        log.clear();

        // a crossing that fails part way (the tripwire is planned after the lamp's reset) has released nothing, because it has lost nothing
        try (Tx tx = Tx.open()) {
            store.write(lamp, p, "L2", tx);
            tx.commit();
        }
        armed.set(true);
        boolean failed = false;
        try {
            cross.accept(TestSlots.DRIFT, new Crossing.Stay<>(p));
        } catch (RuntimeException expected) {
            failed = true;
        }
        armed.set(false);
        c.that(failed, "L8:failure-reaches-the-caller");
        c.that(log.isEmpty() && "L2".equals(store.peek(lamp, p).valueOrNull()), "L9:aborted-crossing-releases-nothing");

        // a DROP at a death releases the value that was there
        cross.accept(TransitionId.DEATH, new Crossing.Stay<>(p));
        c.equal(List.of("lamp:L2"), new ArrayList<>(log), "L9:released-once");
        log.clear();
        try (Tx tx = Tx.open()) {
            store.write(lamp, p, "L3", tx);
            tx.commit();
        }

        // a succession: what is kept moves and is not released; what is dropped or reset is, from the old incarnation, once
        cross.accept(TransitionId.RESPAWN, new Crossing.Succeed<>(p, new FakeHost("p9-again")));
        List<String> afterRespawn = new ArrayList<>(log);
        Collections.sort(afterRespawn);
        c.equal(List.of("bulb:B1", "wick:W1"), afterRespawn, "L9:moved-value-not-released");
        HostRef p2 = store.current(id).orElseThrow(IllegalStateException::new);
        c.that("L3".equals(store.peek(lamp, p2).valueOrNull()) && "F1".equals(store.peek(filament, p2).valueOrNull()), "L9:moved-value-not-released");
        log.clear();

        // an end releases every live value, durable ones too (their stored form stays), once each
        cross.accept(TransitionId.LOGOUT, new Crossing.End<>(p2));
        List<String> afterLogout = new ArrayList<>(log);
        Collections.sort(afterLogout);
        c.equal(List.of("filament:F1", "lamp:L3", "wick:wick0"), afterLogout, "L9:released-once");
    }

    private void randomSequences(Check c) {
        int tokens = 0;
        int aborts = 0;
        for (long seed = 1; seed <= 10; seed++) {
            Simulation sim = new Simulation(c, rig, seed);
            sim.run(300);
            tokens += sim.tokenSightings;
            aborts += sim.abortedCrossings;
        }
        c.that(tokens > 0, "L3:token-exercised");
        c.that(aborts > 0, "L8:aborts-exercised");
    }
}
