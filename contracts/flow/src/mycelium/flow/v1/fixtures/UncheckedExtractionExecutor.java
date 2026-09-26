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
 * The reference executor that does not look at what extraction gave: a source that reports stock but yields nothing
 * falls through to the destination, and the refusal is reported as the transfer's ({@code TRANSFER_FAILED}) instead of
 * the source's ({@code EXTRACTION_FAILED}). Moves nothing and loses nothing, but says the wrong thing about why; caught
 * by T13. Before T13 existed, this change to the reference itself passed every fixture (red team, 2026-09-26).
 */
public final class UncheckedExtractionExecutor {
    private UncheckedExtractionExecutor() {
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
        if (extracted < 0) { // the reference: extracted <= 0
            return new TransferOutcome.Rejected(Reason.EXTRACTION_FAILED);
        }
        long inserted = destination.insert(resource, extracted, tx);
        if (inserted < extracted) {
            long restored = source.insert(resource, extracted - inserted, tx);
            if (restored != extracted - inserted) {
                throw new IllegalStateException("provider failed to restore unaccepted remainder");
            }
        }
        long committed = Math.min(extracted, inserted);
        if (committed <= 0) {
            return new TransferOutcome.Rejected(Reason.TRANSFER_FAILED);
        }
        Receipt receipt = new Receipt(resource, requested, committed);
        tx.onOuterCommit(() -> feedback.transferred(receipt));
        return new TransferOutcome.Committed(receipt);
    }
}
