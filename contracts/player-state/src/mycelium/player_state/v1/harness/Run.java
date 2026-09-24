package mycelium.player_state.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.ContractRun;
import mycelium.player_state.v1.fixtures.LossyStatePort;
import mycelium.player_state.v1.fixtures.MemoryStatePort;
import mycelium.player_state.v1.fixtures.NaiveMigrationUpgrade;
import mycelium.player_state.v1.fixtures.NoOpPort;
import mycelium.player_state.v1.fixtures.RespawnResetPort;
import mycelium.player_state.v1.fixtures.RestartAmnesiaPort;
import mycelium.player_state.v1.fixtures.SkippingMigrationChain;
import mycelium.player_state.v1.model.Migration;
import mycelium.player_state.v1.model.MigrationChain;
import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.Reason;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.StateOutcome;

import java.util.List;
import java.util.Map;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        List<ContractRun.Broken> matrix = List.of(
                new ContractRun.Broken("LossyStatePort",
                        () -> new PlayerStateContractTests(LossyStatePort::new).run(),
                        "T1:round-trip-equal"),
                new ContractRun.Broken("RespawnResetPort",
                        () -> new PlayerStateContractTests(RespawnResetPort::new).run(),
                        "T2:survives-death-respawn"),
                new ContractRun.Broken("RestartAmnesiaPort",
                        () -> new PlayerStateContractTests(RestartAmnesiaPort::new).run(),
                        "T5:survives-restart"),
                new ContractRun.Broken("NoOpPort",
                        () -> new PlayerStateContractTests(NoOpPort::new).run(),
                        "T9:property-0"),
                new ContractRun.Broken("SkippingMigrationChain",
                        Run::skippingMigrationChainCheck,
                        "T7:schema-gap-rejected")
        );

        int exit = ContractRun.run("persistence.player-state@1",
                () -> new PlayerStateContractTests(MemoryStatePort::new).run(), matrix);
        System.exit(exit);
    }

    private static Check skippingMigrationChainCheck() {
        Check c = new Check();
        PlayerId id = new PlayerId("wolf");
        StateBlob v1 = new StateBlob(1, Map.of("hp", "20"));
        Migration m1to2 = migration(1, 2, blob -> new StateBlob(2, blob.fields()));
        Migration m3to4 = migration(3, 4, blob -> new StateBlob(4, blob.fields()));
        // Missing 2 -> 3: a genuine gap. SkippingMigrationChain still claims total(1, 4) == true.
        MigrationChain chain = new SkippingMigrationChain(List.of(m1to2, m3to4));

        StateOutcome outcome = NaiveMigrationUpgrade.upgrade(id, v1, chain, 4);
        c.that(outcome instanceof StateOutcome.Rejected rejected && rejected.reason() == Reason.SCHEMA_GAP,
                "T7:schema-gap-rejected");
        return c;
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
