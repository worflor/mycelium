package mycelium.flow.v1.model;

import java.util.Objects;
import java.util.Set;

public final class Transfer {
    public static final Set<Trait> REQUIRED_TRAITS = Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.IDENTITY_PRESERVING);

    private Transfer() {
    }

    public static TransferOutcome execute(FlowBackend source, FlowBackend destination, ResourceKey resource,
                                          long requested, FlowTransaction tx, Feedback feedback) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (requested <= 0) {
            return new TransferOutcome.Rejected(Reason.ZERO_REQUEST);
        }
        if (!source.traits().containsAll(REQUIRED_TRAITS) || !destination.traits().containsAll(REQUIRED_TRAITS)) {
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
        tx.onOuterCommit(() -> feedback.transferred(receipt));
        return new TransferOutcome.Committed(receipt);
    }
}
