package mycelium.chat_coverage.v1.fixtures;

import mycelium.chat_coverage.v1.model.Cause;
import mycelium.chat_coverage.v1.model.Coverage;
import mycelium.chat_coverage.v1.model.Snapshot;
import mycelium.chat_coverage.v1.model.CoverageLedger;
import mycelium.chat_coverage.v1.model.Status;

/** Rejection-matrix implementations for chat.coverage@1. */
public final class BrokenCoverages {
    private BrokenCoverages() {
    }

    public static Coverage noOp() {
        return new Delegating() {
            @Override
            public Snapshot commitDurable(Snapshot current, long sequence) {
                return current;
            }
        };
    }

    public static Coverage incompleteEmpty() {
        return new Delegating() {
            @Override
            public Snapshot begin(String runId) {
                return new Snapshot(runId, 0, Status.INCOMPLETE, Cause.UNKNOWN_START);
            }
        };
    }

    public static Coverage completeUnknown() {
        return new Delegating() {
            @Override
            public Snapshot unknownStart(String runId) {
                return new Snapshot(runId, 0, Status.COMPLETE, Cause.NONE);
            }
        };
    }

    public static Coverage gapAccepting() {
        return new Delegating() {
            @Override
            public Snapshot commitDurable(Snapshot current, long sequence) {
                if (sequence <= current.committedThrough()) {
                    return super.commitDurable(current, sequence);
                }
                return new Snapshot(current.runId(), sequence, Status.COMPLETE, Cause.NONE);
            }
        };
    }

    public static Coverage silentLoss() {
        return new Delegating() {
            @Override
            public Snapshot markIncomplete(Snapshot current, Cause cause) {
                return current;
            }
        };
    }

    public static Coverage launderingRestore() {
        return new Delegating() {
            @Override
            public Snapshot restore(Snapshot saved) {
                return new Snapshot(saved.runId(), saved.committedThrough(), Status.COMPLETE, Cause.NONE);
            }
        };
    }

    public static Coverage replacingCause() {
        return new Delegating() {
            @Override
            public Snapshot markIncomplete(Snapshot current, Cause cause) {
                return new Snapshot(current.runId(), current.committedThrough(), Status.INCOMPLETE, cause);
            }
        };
    }

    public static Coverage resettingTransition() {
        return new Delegating() {
            @Override
            public Snapshot commitDurable(Snapshot current, long sequence) {
                if (sequence != current.committedThrough() + 1) {
                    return super.commitDurable(current, sequence);
                }
                return new Snapshot("different-run", sequence, current.status(), current.cause());
            }
        };
    }

    private abstract static class Delegating implements Coverage {
        private final Coverage reference = new CoverageLedger();

        @Override
        public Snapshot begin(String runId) {
            return reference.begin(runId);
        }

        @Override
        public Snapshot unknownStart(String runId) {
            return reference.unknownStart(runId);
        }

        @Override
        public Snapshot commitDurable(Snapshot current, long sequence) {
            return reference.commitDurable(current, sequence);
        }

        @Override
        public Snapshot markIncomplete(Snapshot current, Cause cause) {
            return reference.markIncomplete(current, cause);
        }

        @Override
        public Snapshot restore(Snapshot saved) {
            return reference.restore(saved);
        }
    }
}
