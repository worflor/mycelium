package mycelium.world_copy.v1.model;

public sealed interface CopyOutcome {
    record Committed(CopyReceipt receipt) implements CopyOutcome {
        public Committed {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements CopyOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements CopyOutcome {
    }
}
