package mycelium.player_state.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.player_state.v1.fixtures.MemoryStatePort;
import mycelium.player_state.v1.model.Migration;
import mycelium.player_state.v1.model.MigrationChain;
import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.PlayerState;
import mycelium.player_state.v1.model.Reason;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.StateOutcome;
import mycelium.player_state.v1.model.Trait;
import mycelium.player_state.v1.model.Tx;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class PlayerStateContractTests {
    private final Supplier<MemoryStatePort> portFactory;

    public PlayerStateContractTests(Supplier<MemoryStatePort> portFactory) {
        this.portFactory = portFactory;
    }

    public Check run() {
        Check check = new Check();
        t1PositiveRoundTrip(check);
        t2SurvivesDeathRespawn(check);
        t3SurvivesDimensionChange(check);
        t4SurvivesRelog(check);
        t5SurvivesRestart(check);
        t6SchemaVersionedUpgrade(check);
        t7SchemaGapRejected(check);
        t8FinalityViaTx(check);
        t9SeededPropertyFixture(check);
        return check;
    }

    private void t1PositiveRoundTrip(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob blob = new StateBlob(1, Map.of("hp", "20", "name", "Wolf"));
        FeedbackSink feedback = new FeedbackSink();

        Tx tx = Tx.open();
        StateOutcome outcome = PlayerState.write(port, id, blob, tx, feedback);
        c.that(outcome instanceof StateOutcome.Committed, "T1:outcome-committed");
        tx.commit();
        c.equal(Optional.of(blob), port.read(id), "T1:round-trip-equal");
        c.that(feedback.events().size() == 1, "T1:feedback-once");

        Tx tx2 = Tx.open();
        StateOutcome emptyOutcome = PlayerState.write(port, id, new StateBlob(1, Map.of()), tx2, feedback);
        c.that(emptyOutcome instanceof StateOutcome.Rejected rejected && rejected.reason() == Reason.EMPTY_BLOB,
                "T1:empty-blob-rejected");
        tx2.commit();
        c.equal(Optional.of(blob), port.read(id), "T1:empty-blob-no-mutation");

        MemoryStatePort unqualified = new MemoryStatePort() {
            @Override
            public Set<Trait> traits() {
                return Set.of(Trait.RESPAWN_SAFE);
            }
        };
        Tx tx3 = Tx.open();
        StateOutcome unsupported = PlayerState.write(unqualified, id, blob, tx3, feedback);
        c.that(unsupported instanceof StateOutcome.Unsupported, "T1:unsupported-trait");
        tx3.commit();
        c.that(unqualified.read(id).isEmpty(), "T1:unsupported-trait-no-mutation");
    }

    private void t2SurvivesDeathRespawn(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob blob = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, blob);
        port.simulateDeathRespawn(id);
        c.equal(Optional.of(blob), port.read(id), "T2:survives-death-respawn");
    }

    private void t3SurvivesDimensionChange(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob blob = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, blob);
        port.simulateDimensionChange(id);
        c.equal(Optional.of(blob), port.read(id), "T3:survives-dimension-change");
    }

    private void t4SurvivesRelog(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob blob = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, blob);
        port.simulateRelog(id);
        c.equal(Optional.of(blob), port.read(id), "T4:survives-relog");
    }

    private void t5SurvivesRestart(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob blob = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, blob);
        port.simulateRestart();
        c.equal(Optional.of(blob), port.read(id), "T5:survives-restart");
    }

    private void t6SchemaVersionedUpgrade(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob v1 = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, v1);

        Migration v1v2 = migration(1, 2, blob -> {
            Map<String, String> fields = new LinkedHashMap<>(blob.fields());
            fields.put("migrated", "true");
            return new StateBlob(2, fields);
        });
        MigrationChain chain = new MigrationChain(List.of(v1v2));
        c.that(chain.total(1, 2), "T6:chain-total");

        Optional<StateBlob> current = PlayerState.readCurrent(port, id, chain, 2);
        c.that(current.isPresent(), "T6:upgraded-present");
        current.ifPresent(blob -> {
            c.equalLong(2, blob.schema(), "T6:upgraded-schema");
            c.equal("20", blob.fields().get("hp"), "T6:upgraded-original-field");
            c.equal("true", blob.fields().get("migrated"), "T6:upgraded-new-field");
        });
    }

    private void t7SchemaGapRejected(Check c) {
        MemoryStatePort port = portFactory.get();
        PlayerId id = new PlayerId("wolf");
        StateBlob v1 = new StateBlob(1, Map.of("hp", "20"));
        writeCommitted(port, id, v1);

        Migration v1v2 = migration(1, 2, blob -> new StateBlob(2, blob.fields()));
        // no 2 -> 3 step registered: a gap
        MigrationChain chain = new MigrationChain(List.of(v1v2));
        c.that(!chain.total(1, 3), "T7:chain-declares-gap");

        StateOutcome outcome = PlayerState.upgrade(id, v1, chain, 3);
        c.that(outcome instanceof StateOutcome.Rejected rejected && rejected.reason() == Reason.SCHEMA_GAP,
                "T7:schema-gap-rejected");

        Optional<StateBlob> current = PlayerState.readCurrent(port, id, chain, 3);
        c.that(current.isEmpty(), "T7:no-partial-upgrade");
    }

    private void t8FinalityViaTx(Check c) {
        PlayerId id = new PlayerId("wolf");
        StateBlob original = new StateBlob(1, Map.of("hp", "20"));
        StateBlob updated = new StateBlob(1, Map.of("hp", "5"));

        MemoryStatePort abortPort = portFactory.get();
        writeCommitted(abortPort, id, original);
        FeedbackSink abortFeedback = new FeedbackSink();
        Tx outer = Tx.open();
        Tx nested = outer.openNested();
        PlayerState.write(abortPort, id, updated, nested, abortFeedback);
        nested.commit();
        outer.abort();
        c.equal(Optional.of(original), abortPort.read(id), "T8:abort-restores-previous");
        c.that(abortFeedback.events().isEmpty(), "T8:no-feedback-after-abort");

        MemoryStatePort commitPort = portFactory.get();
        writeCommitted(commitPort, id, original);
        FeedbackSink commitFeedback = new FeedbackSink();
        Tx outer2 = Tx.open();
        Tx nested2 = outer2.openNested();
        PlayerState.write(commitPort, id, updated, nested2, commitFeedback);
        nested2.commit();
        c.that(commitFeedback.events().isEmpty(), "T8:no-feedback-before-outer-commit");
        outer2.commit();
        c.equal(Optional.of(updated), commitPort.read(id), "T8:commit-applies-update");
        c.that(commitFeedback.events().size() == 1, "T8:feedback-once-after-outer-commit");
    }

    private void t9SeededPropertyFixture(Check c) {
        MemoryStatePort port = portFactory.get();
        Seeded seeded = new Seeded(4217);
        String[] keys = {"hp", "hunger", "xp", "mana", "coords", "class", "guild"};

        for (int i = 0; i < 30; i++) {
            PlayerId id = new PlayerId("player-" + i + "-" + seeded.nextInt(1_000_000));
            Map<String, String> fields = new HashMap<>();
            int fieldCount = seeded.nextInt(1, 5);
            for (int f = 0; f < fieldCount; f++) {
                String key = keys[seeded.nextInt(keys.length)];
                fields.put(key, Integer.toString(seeded.nextInt(0, 10_000)));
            }
            StateBlob blob = new StateBlob(1, fields);
            writeCommitted(port, id, blob);

            port.simulateDeathRespawn(id);
            port.simulateDimensionChange(id);
            port.simulateRelog(id);
            port.simulateRestart();

            c.equal(Optional.of(blob), port.read(id), "T9:property-" + i);
        }
    }

    private void writeCommitted(MemoryStatePort port, PlayerId id, StateBlob blob) {
        Tx tx = Tx.open();
        StateOutcome outcome = PlayerState.write(port, id, blob, tx, new FeedbackSink());
        if (!(outcome instanceof StateOutcome.Committed)) {
            throw new IllegalStateException("fixture setup expected Committed, got " + outcome);
        }
        tx.commit();
    }

    private static Migration migration(int from, int to, java.util.function.Function<StateBlob, StateBlob> fn) {
        return new Migration() {
            @Override
            public int from() {
                return from;
            }

            @Override
            public int to() {
                return to;
            }

            @Override
            public StateBlob migrate(StateBlob blob) {
                return fn.apply(blob);
            }
        };
    }
}
