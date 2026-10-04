package mycelium.chat_coverage.v1.model;

import java.util.Objects;

/** Reference transitions for chat.coverage@1. */
public final class CoverageLedger implements Coverage {
    @Override
    public Snapshot begin(String runId) {
        return new Snapshot(runId, 0, Status.COMPLETE, Cause.NONE);
    }

    @Override
    public Snapshot unknownStart(String runId) {
        return new Snapshot(runId, 0, Status.INCOMPLETE, Cause.UNKNOWN_START);
    }

    @Override
    public Snapshot commitDurable(Snapshot current, long sequence) {
        Objects.requireNonNull(current, "current");
        if (current.committedThrough() == Long.MAX_VALUE || sequence <= current.committedThrough()) {
            return incomplete(current, Cause.INVALID_SEQUENCE);
        }
        if (sequence != current.committedThrough() + 1) {
            return incomplete(current, Cause.SEQUENCE_GAP);
        }
        return new Snapshot(current.runId(), sequence, current.status(), current.cause());
    }

    @Override
    public Snapshot markIncomplete(Snapshot current, Cause cause) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(cause, "cause");
        if (cause == Cause.NONE) {
            throw new IllegalArgumentException("loss cause must be concrete");
        }
        return incomplete(current, cause);
    }

    @Override
    public Snapshot restore(Snapshot saved) {
        Objects.requireNonNull(saved, "saved");
        return new Snapshot(saved.runId(), saved.committedThrough(), saved.status(), saved.cause());
    }

    private Snapshot incomplete(Snapshot current, Cause cause) {
        Cause first = current.status() == Status.INCOMPLETE ? current.cause() : cause;
        return new Snapshot(current.runId(), current.committedThrough(), Status.INCOMPLETE, first);
    }
}
