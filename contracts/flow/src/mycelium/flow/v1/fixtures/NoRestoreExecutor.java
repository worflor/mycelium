package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.Feedback;
import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.Reason;
import mycelium.flow.v1.model.Receipt;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Transfer;
import mycelium.flow.v1.model.TransferOutcome;

/**
 * The reference executor without its restore: when a destination admits less than was extracted, the remainder is
 * dropped instead of returned to the source. Violates L1 (conservation) and L4 (nothing outside a receipt); caught by T11,
 * whose destination promises more room than it has. Before T11 existed, deleting the restore from the reference itself
 * still passed every fixture (red team, 2026-09-26).
 */
public final class NoRestoreExecutor {
    private NoRestoreExecutor() {
    }

    public static TransferOutcome execute(FlowBackend source, FlowBackend destination, ResourceKey resource,
                                          long requested, FlowTransaction tx, Feedback feedback) {
        if (requested <= 0) {
            return new TransferOutcome.Rejected(Reason.ZERO_REQUEST);
        }
        if (!source.traits().containsAll(Transfer.REQUIRED_TRAITS)
                || !destination.traits().containsAll(Transfer.REQUIRED_TRAITS)) {
            return new TransferOutcome.Unsupported("provider lacks required traits");
        }
        long available = source.available(resource);
        long capacity = destination.admissibleCapacity(resource);
        long planned = Math.min(requested, Math.min(available, capacity));
        if (planned == 0) {
            return new TransferOutcome.Rejected(available == 0 ? Reason.EMPTY_SOURCE : Reason.FULL_DESTINATION);
        }
        long extracted = source.extract(resource, planned, tx);
        if (extracted <= 0) {
            return new TransferOutcome.Rejected(Reason.EXTRACTION_FAILED);
        }
        long inserted = destination.insert(resource, extracted, tx);
        // (the reference restores extracted - inserted to the source here)
        long committed = Math.min(extracted, inserted);
        if (committed <= 0) {
            return new TransferOutcome.Rejected(Reason.TRANSFER_FAILED);
        }
        Receipt receipt = new Receipt(resource, requested, committed);
        tx.onOuterCommit(() -> feedback.transferred(receipt));
        return new TransferOutcome.Committed(receipt);
    }
}
