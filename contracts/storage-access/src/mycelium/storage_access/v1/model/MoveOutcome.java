package mycelium.storage_access.v1.model;

import java.util.Objects;

/**
 * A move's outcome. {@code Moved} is provisional until the caller's outermost transaction commits, as every receipt under a
 * parent is (DESIGN §7); every other outcome moved nothing.
 */
public sealed interface MoveOutcome {
    /** The move holds in the caller's transaction: exactly {@code receipt.moved()} left the source and reached the destination. */
    record Moved(MoveReceipt receipt) implements MoveOutcome {
        public Moved {
            Objects.requireNonNull(receipt, "receipt");
        }
    }

    /** Nothing moved, for this reason. */
    record Rejected(Reason reason) implements MoveOutcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The platform reaches no storage of the goods' kind; decided before anything is looked up. */
    record Unsupported(String detail) implements MoveOutcome {
    }

    /** A store the move touched vanished inside it; the move was released and nothing moved. */
    record Stale(StoreAddress vanished) implements MoveOutcome {
        public Stale {
            Objects.requireNonNull(vanished, "vanished");
        }
    }
}
