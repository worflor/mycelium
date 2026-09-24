package mycelium.flow.v1.model;

public sealed interface TransferOutcome {
    record Committed(Receipt receipt) implements TransferOutcome {
        public Committed {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements TransferOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements TransferOutcome {
    }

    record Stale() implements TransferOutcome {
    }
}
