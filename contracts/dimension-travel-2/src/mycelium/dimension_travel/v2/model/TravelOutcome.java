package mycelium.dimension_travel.v2.model;

public sealed interface TravelOutcome {
    record Committed(TravelReceipt receipt) implements TravelOutcome {
        public Committed {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements TravelOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements TravelOutcome {
    }

    /** Move threw {@link PortFailure}: outcome is unconfirmed, never collapsed into Rejected. */
    record Indeterminate(String detail) implements TravelOutcome {
    }
}
