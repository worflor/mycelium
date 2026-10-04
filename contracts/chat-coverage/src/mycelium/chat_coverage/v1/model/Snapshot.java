package mycelium.chat_coverage.v1.model;

import java.util.Objects;

/** Immutable, loader-neutral coverage state that a consumer may serialize as scalar fields. */
public final class Snapshot {
    private final String runId;
    private final long committedThrough;
    private final Status status;
    private final Cause cause;

    public Snapshot(String runId, long committedThrough, Status status, Cause cause) {
        if (runId == null || runId.trim().isEmpty() || runId.length() > 128) {
            throw new IllegalArgumentException("runId must be nonblank and at most 128 characters");
        }
        if (committedThrough < 0) {
            throw new IllegalArgumentException("committedThrough must be nonnegative");
        }
        this.status = Objects.requireNonNull(status, "status");
        this.cause = Objects.requireNonNull(cause, "cause");
        if (status == Status.COMPLETE && cause != Cause.NONE) {
            throw new IllegalArgumentException("complete snapshots require cause NONE");
        }
        if (status == Status.INCOMPLETE && cause == Cause.NONE) {
            throw new IllegalArgumentException("incomplete snapshots require a cause");
        }
        this.runId = runId;
        this.committedThrough = committedThrough;
    }

    public String runId() {
        return runId;
    }

    public long committedThrough() {
        return committedThrough;
    }

    public Status status() {
        return status;
    }

    public Cause cause() {
        return cause;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Snapshot)) {
            return false;
        }
        Snapshot snapshot = (Snapshot) other;
        return committedThrough == snapshot.committedThrough
                && runId.equals(snapshot.runId)
                && status == snapshot.status
                && cause == snapshot.cause;
    }

    @Override
    public int hashCode() {
        return Objects.hash(runId, committedThrough, status, cause);
    }

    @Override
    public String toString() {
        return "Snapshot{" + runId + "," + committedThrough + "," + status + "," + cause + "}";
    }
}
