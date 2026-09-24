package mycelium.dimension_travel.v2.model;

public sealed interface RecoverOutcome {
    record Recovered(RecoverReceipt receipt) implements RecoverOutcome {
        public Recovered {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements RecoverOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements RecoverOutcome {
    }

    /** The way home failed or could not be confirmed: the journal stays open, to be tried again next session. */
    record Indeterminate(String detail) implements RecoverOutcome {
    }
}
