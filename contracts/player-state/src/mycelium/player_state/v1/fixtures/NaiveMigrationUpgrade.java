package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.MigrationChain;
import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.Reason;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.StateOutcome;
import mycelium.player_state.v1.model.StateReceipt;

/**
 * A deliberately naive stand-in for {@code PlayerState.upgrade} that trusts {@link MigrationChain#total}
 * as the sole gate and never performs the real walk. Paired with {@link SkippingMigrationChain} (which
 * lies about {@code total()}), this fabricates a {@code Committed} outcome over an actual schema gap:
 * exactly the mistake the real executor is built to refuse to make.
 */
public final class NaiveMigrationUpgrade {
    private NaiveMigrationUpgrade() {
    }

    public static StateOutcome upgrade(PlayerId id, StateBlob blob, MigrationChain chain, int target) {
        if (blob.schema() == target) {
            return new StateOutcome.Committed(new StateReceipt(id, target));
        }
        if (!chain.total(blob.schema(), target)) {
            return new StateOutcome.Rejected(Reason.SCHEMA_GAP);
        }
        // Trusts the declaration; does not actually walk the registered steps.
        return new StateOutcome.Committed(new StateReceipt(id, target));
    }
}
