package mycelium.player_state.v1.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PlayerState {
    private PlayerState() {
    }

    /** Writes a blob under the given transaction. Rejects an empty blob before any mutation; rejects an
     * unqualified port (missing DURABLE) before any mutation. Feedback fires only at the outermost commit. */
    public static StateOutcome write(StatePort port, PlayerId id, StateBlob blob, Tx tx, Feedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (blob.fields().isEmpty()) {
            return new StateOutcome.Rejected(Reason.EMPTY_BLOB);
        }
        Set<Trait> traits = port.traits();
        if (!traits.contains(Trait.DURABLE)) {
            return new StateOutcome.Unsupported("port lacks DURABLE trait");
        }

        port.write(id, blob, tx);
        StateReceipt receipt = new StateReceipt(id, blob.schema());
        tx.onOuterCommit(() -> feedback.written(receipt));
        return new StateOutcome.Committed(receipt);
    }

    /** Upgrades a blob already in hand to {@code targetSchema} through {@code chain}. Never trusts
     * {@link MigrationChain#total}; always performs the real walk and turns a gap into
     * {@code Rejected(SCHEMA_GAP)} rather than a partial upgrade. */
    public static StateOutcome upgrade(PlayerId id, StateBlob blob, MigrationChain chain, int targetSchema) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(blob, "blob");
        Objects.requireNonNull(chain, "chain");

        if (blob.schema() == targetSchema) {
            return new StateOutcome.Committed(new StateReceipt(id, targetSchema));
        }
        try {
            StateBlob upgraded = chain.upgrade(blob, targetSchema);
            return new StateOutcome.Committed(new StateReceipt(id, upgraded.schema()));
        } catch (MigrationGapException e) {
            return new StateOutcome.Rejected(Reason.SCHEMA_GAP);
        }
    }

    /** Reads the stored blob, migrated to {@code currentSchema} through {@code chain}. Empty if nothing is
     * stored, or if the walk from the stored schema to {@code currentSchema} hits a gap: never a partial
     * upgrade. */
    public static Optional<StateBlob> readCurrent(StatePort port, PlayerId id, MigrationChain chain, int currentSchema) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(chain, "chain");

        Optional<StateBlob> stored = port.read(id);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        StateBlob blob = stored.get();
        if (blob.schema() == currentSchema) {
            return Optional.of(blob);
        }
        try {
            return Optional.of(chain.upgrade(blob, currentSchema));
        } catch (MigrationGapException e) {
            return Optional.empty();
        }
    }
}
