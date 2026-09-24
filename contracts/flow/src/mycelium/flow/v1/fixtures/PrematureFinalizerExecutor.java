package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.Feedback;
import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.Reason;
import mycelium.flow.v1.model.Receipt;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.Transfer;
import mycelium.flow.v1.model.TransferOutcome;

public final class PrematureFinalizerExecutor {
    private PrematureFinalizerExecutor() {
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
            if (available == 0) {
                return new TransferOutcome.Rejected(Reason.EMPTY_SOURCE);
            }
            return new TransferOutcome.Rejected(Reason.FULL_DESTINATION);
        }

        long extracted = source.extract(resource, planned, tx);
        if (extracted <= 0) {
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
        feedback.transferred(receipt);
        return new TransferOutcome.Committed(receipt);
    }
}
