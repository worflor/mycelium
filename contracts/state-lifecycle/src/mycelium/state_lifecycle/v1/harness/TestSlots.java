package mycelium.state_lifecycle.v1.harness;

import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.Policy;
import mycelium.state_lifecycle.v1.model.Shape;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Transitions;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The slots the harness declares, written the way a mod would: one policy per shape of transition, with the odd one out
 * named. Players hold a session map that must end, durable values (one with a migration at a consumer transition), an
 * EXCLUSIVE token and a tripwire that throws on demand; entities, levels and the server hold a few of their own.
 */
final class TestSlots {
    static final TransitionId DRIFT = new TransitionId("t:drift");

    final Transitions transitions = Transitions.builder().declare("t:drift", Shape.STAYS, HostKind.PLAYER, HostKind.ENTITY).build();
    /** While set, the tripwire's migration throws: a step of a crossing that fails part way. */
    final AtomicBoolean armed = new AtomicBoolean();

    final Slot<String> sessionMap;
    final Slot<String> record;
    final Slot<Map<String, Integer>> counts;
    final Slot<String> token;
    final Slot<Integer> tripwire;
    final Slot<String> entityMark;
    final Slot<String> entityToken;
    final Slot<String> entityRecord;
    final Slot<Integer> levelCount;
    final Slot<Boolean> serverFlag;
    final List<Slot<?>> all;

    TestSlots() {
        sessionMap = shaped(Slot.session("t:session_map", HostKind.PLAYER, "none"), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), Policy.drop(), Policy.drop())
                .on(TransitionId.DEATH, Policy.drop()).build();
        record = shaped(Slot.durable("t:record", HostKind.PLAYER, "", Codecs.STRING), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.copy())
                .on(DRIFT, Policy.migrate(s -> s + "+"))
                .dependsOn(sessionMap.id()).build();
        Map<String, Integer> noCounts = Collections.emptyMap();
        counts = shaped(Slot.durable("t:counts", HostKind.PLAYER, noCounts, Codecs.COUNTS), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.copy()).build();
        token = shaped(Slot.durable("t:token", HostKind.PLAYER, "tok", Codecs.STRING), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), Policy.exclusive()).build();
        Policy<Integer> strain = Policy.migrate(n -> {
            if (armed.get()) {
                throw new IllegalStateException("tripwire");
            }
            return n + 1;
        });
        tripwire = shaped(Slot.session("t:zz_tripwire", HostKind.PLAYER, 0), HostKind.PLAYER,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), strain, strain)
                .on(DRIFT, strain)
                .dependsOn(sessionMap.id(), record.id(), counts.id(), token.id()).build();

        entityMark = shaped(Slot.session("t:e_mark", HostKind.ENTITY, "unmarked"), HostKind.ENTITY,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.drop(), null, Policy.drop()).build();
        entityToken = shaped(Slot.durable("t:e_token", HostKind.ENTITY, "etok", Codecs.STRING), HostKind.ENTITY,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), null, Policy.exclusive()).build();
        entityRecord = shaped(Slot.durable("t:e_record", HostKind.ENTITY, "", Codecs.STRING), HostKind.ENTITY,
                Policy.keep(), Policy.keep(), Policy.keep(), Policy.keep(), null, Policy.copy())
                .on(TransitionId.REMOVE, Policy.drop()).build();
        levelCount = shaped(Slot.session("t:l_count", HostKind.LEVEL, 0), HostKind.LEVEL,
                Policy.keep(), null, Policy.reset(), Policy.drop(), null, null).build();
        serverFlag = shaped(Slot.session("t:s_flag", HostKind.SERVER, false), HostKind.SERVER,
                Policy.keep(), null, Policy.reset(), Policy.drop(), null, null).build();
        all = List.of(sessionMap, record, counts, token, tripwire, entityMark, entityToken, entityRecord, levelCount, serverFlag);
    }

    SlotRegistry registry() {
        return SlotRegistry.of(transitions, all);
    }

    /** One policy per shape, on the transitions of that shape which apply to the scope; null leaves the shape undeclared. */
    <T> Slot.Builder<T> shaped(Slot.Builder<T> b, HostKind scope, Policy<T> saves, Policy<T> stays, Policy<T> begins,
                               Policy<T> ends, Policy<T> succeeds, Policy<T> forks) {
        put(b, scope, Shape.SAVES, saves);
        put(b, scope, Shape.STAYS, stays);
        put(b, scope, Shape.BEGINS, begins);
        put(b, scope, Shape.ENDS, ends);
        put(b, scope, Shape.SUCCEEDS, succeeds);
        put(b, scope, Shape.FORKS, forks);
        return b;
    }

    private <T> void put(Slot.Builder<T> b, HostKind scope, Shape shape, Policy<T> policy) {
        if (policy == null) {
            return;
        }
        for (TransitionId t : transitions.shaped(shape)) {
            if (transitions.get(t).admits(scope)) {
                b.on(t, policy);
            }
        }
    }
}
