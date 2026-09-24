package mycelium.player_state.v1.model;

public sealed interface StateOutcome {
    record Committed(StateReceipt receipt) implements StateOutcome {
        public Committed {
            if (receipt == null) {
                throw new IllegalArgumentException("receipt");
            }
        }
    }

    record Rejected(Reason reason) implements StateOutcome {
        public Rejected {
            if (reason == null) {
                throw new IllegalArgumentException("reason");
            }
        }
    }

    record Unsupported(String detail) implements StateOutcome {
    }
}
