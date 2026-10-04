package mycelium.chat_coverage.v1.model;

public interface Coverage {
    Snapshot begin(String runId);

    Snapshot unknownStart(String runId);

    Snapshot commitDurable(Snapshot current, long sequence);

    Snapshot markIncomplete(Snapshot current, Cause cause);

    Snapshot restore(Snapshot saved);
}
