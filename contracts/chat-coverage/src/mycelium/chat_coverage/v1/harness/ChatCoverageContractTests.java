package mycelium.chat_coverage.v1.harness;

import mycelium.chat_coverage.v1.model.Cause;
import mycelium.chat_coverage.v1.model.Coverage;
import mycelium.chat_coverage.v1.model.Snapshot;
import mycelium.chat_coverage.v1.model.Status;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class ChatCoverageContractTests {
    private final Supplier<Coverage> coverages;

    public ChatCoverageContractTests(Supplier<Coverage> coverages) {
        this.coverages = coverages;
    }

    public Check run() {
        Check c = new Check();
        l1EstablishedEmptyRun(c);
        l2UnknownStart(c);
        l3OnlyNextSequence(c);
        l4LossIsSticky(c);
        l5RestoreNeverHeals(c);
        l6FirstCauseIsStable(c);
        l7SnapshotIsImmutable(c);
        p1DurableNextEvent(c);
        seededHistory(c);
        return c;
    }

    private void l1EstablishedEmptyRun(Check c) {
        Snapshot empty = coverages.get().begin("run-a");
        c.that(empty.status() == Status.COMPLETE && empty.committedThrough() == 0 && empty.cause() == Cause.NONE,
                "L1:established-empty-run-is-complete");
    }

    private void l2UnknownStart(Check c) {
        Snapshot unknown = coverages.get().unknownStart("run-a");
        c.that(unknown.status() == Status.INCOMPLETE && unknown.cause() == Cause.UNKNOWN_START,
                "L2:unknown-start-is-incomplete");
    }

    private void l3OnlyNextSequence(Check c) {
        Coverage coverage = coverages.get();
        Snapshot start = coverage.begin("run-a");
        Snapshot negative = coverage.commitDurable(start, -1);
        Snapshot zero = coverage.commitDurable(start, 0);
        Snapshot gap = coverage.commitDurable(start, 2);
        Snapshot one = coverage.commitDurable(start, 1);
        Snapshot duplicate = coverage.commitDurable(one, 1);
        Snapshot overflow = coverage.commitDurable(
                new Snapshot("run-a", Long.MAX_VALUE, Status.COMPLETE, Cause.NONE), Long.MAX_VALUE);
        Snapshot lastPossible = coverage.commitDurable(
                new Snapshot("run-a", Long.MAX_VALUE - 1, Status.COMPLETE, Cause.NONE), Long.MAX_VALUE);
        c.that(invalidAt(negative, "run-a", 0) && negative.cause() == Cause.INVALID_SEQUENCE
                        && invalidAt(zero, "run-a", 0) && zero.cause() == Cause.INVALID_SEQUENCE
                        && invalidAt(gap, "run-a", 0) && gap.cause() == Cause.SEQUENCE_GAP
                        && one.status() == Status.COMPLETE && one.committedThrough() == 1
                        && invalidAt(duplicate, "run-a", 1) && duplicate.cause() == Cause.INVALID_SEQUENCE
                        && invalidAt(overflow, "run-a", Long.MAX_VALUE) && overflow.cause() == Cause.INVALID_SEQUENCE
                        && lastPossible.status() == Status.COMPLETE
                        && lastPossible.committedThrough() == Long.MAX_VALUE && lastPossible.runId().equals("run-a"),
                "L3:only-next-sequence-advances");
    }

    private boolean invalidAt(Snapshot value, String runId, long sequence) {
        return value.status() == Status.INCOMPLETE && value.committedThrough() == sequence
                && value.runId().equals(runId);
    }

    private void l4LossIsSticky(Check c) {
        Coverage coverage = coverages.get();
        boolean everyCauseSticks = true;
        for (Cause cause : concreteCauses()) {
            Snapshot incomplete = coverage.markIncomplete(coverage.begin("run-a"), cause);
            Snapshot appended = coverage.commitDurable(incomplete, 1);
            everyCauseSticks &= incomplete.status() == Status.INCOMPLETE && incomplete.cause() == cause
                    && appended.status() == Status.INCOMPLETE && appended.committedThrough() == 1
                    && appended.cause() == cause && appended.runId().equals("run-a");
        }
        c.that(everyCauseSticks, "L4:loss-is-sticky");
    }

    private void l5RestoreNeverHeals(Check c) {
        Coverage coverage = coverages.get();
        Snapshot empty = coverage.begin("empty-run");
        Snapshot advanced = coverage.commitDurable(coverage.commitDurable(empty, 1), 2);
        boolean preserved = coverage.restore(empty).equals(empty) && coverage.restore(advanced).equals(advanced);
        for (Cause cause : concreteCauses()) {
            Snapshot incomplete = new Snapshot("run-a", 7, Status.INCOMPLETE, cause);
            Snapshot restored = coverage.restore(incomplete);
            preserved &= restored != incomplete && restored.equals(incomplete)
                    && restored.status() == Status.INCOMPLETE && restored.cause() == cause
                    && restored.committedThrough() == 7 && restored.runId().equals("run-a");
        }
        c.that(preserved, "L5:restore-never-heals");
    }

    private void l6FirstCauseIsStable(Check c) {
        Coverage coverage = coverages.get();
        boolean stable = true;
        for (Cause firstCause : concreteCauses()) {
            Snapshot first = coverage.markIncomplete(coverage.begin("run-a"), firstCause);
            for (Cause laterCause : concreteCauses()) {
                Snapshot next = coverage.markIncomplete(first, laterCause);
                stable &= next.status() == Status.INCOMPLETE && next.cause() == firstCause
                        && next.committedThrough() == 0 && next.runId().equals("run-a");
            }
        }
        c.that(stable, "L6:first-loss-cause-is-stable");
    }

    private void l7SnapshotIsImmutable(Check c) {
        Coverage coverage = coverages.get();
        Snapshot before = coverage.begin("run-a");
        Snapshot advanced = coverage.commitDurable(before, 1);
        Snapshot lost = coverage.markIncomplete(before, Cause.CAPACITY_REACHED);
        Snapshot restored = coverage.restore(before);
        boolean inputUnchanged = before.status() == Status.COMPLETE && before.committedThrough() == 0
                && before.cause() == Cause.NONE && before.runId().equals("run-a");
        boolean transitionsPreserveIdentity = advanced.runId().equals("run-a") && advanced.committedThrough() == 1
                && lost.runId().equals("run-a") && lost.committedThrough() == 0
                && restored.runId().equals("run-a") && restored.committedThrough() == 0;
        boolean invalidRejected = rejects(() -> new Snapshot("", 0, Status.COMPLETE, Cause.NONE))
                && rejects(() -> new Snapshot("  ", 0, Status.COMPLETE, Cause.NONE))
                && rejects(() -> new Snapshot("x".repeat(129), 0, Status.COMPLETE, Cause.NONE))
                && rejects(() -> new Snapshot("run-a", -1, Status.COMPLETE, Cause.NONE))
                && rejects(() -> new Snapshot("run-a", 0, Status.COMPLETE, Cause.OPEN_FAILED))
                && rejects(() -> new Snapshot("run-a", 0, Status.INCOMPLETE, Cause.NONE))
                && rejects(() -> new Snapshot("run-a", 0, null, Cause.NONE))
                && rejects(() -> new Snapshot("run-a", 0, Status.COMPLETE, null))
                && rejects(() -> coverage.begin(null))
                && rejects(() -> coverage.unknownStart(null))
                && rejects(() -> coverage.commitDurable(null, 1))
                && rejects(() -> coverage.markIncomplete(before, null))
                && rejects(() -> coverage.markIncomplete(before, Cause.NONE))
                && rejects(() -> coverage.markIncomplete(null, Cause.APPEND_FAILED))
                && rejects(() -> coverage.restore(null));
        c.that(inputUnchanged && transitionsPreserveIdentity && invalidRejected,
                "L7:snapshot-is-immutable");
    }

    private boolean rejects(Runnable operation) {
        try {
            operation.run();
            return false;
        } catch (IllegalArgumentException | NullPointerException expected) {
            return true;
        }
    }

    private void p1DurableNextEvent(Check c) {
        Coverage coverage = coverages.get();
        Snapshot one = coverage.commitDurable(coverage.begin("run-a"), 1);
        Snapshot two = coverage.commitDurable(one, 2);
        c.that(one.status() == Status.COMPLETE && one.committedThrough() == 1
                        && two.status() == Status.COMPLETE && two.committedThrough() == 2,
                "P1:durable-next-event-is-recorded");
    }

    private void seededHistory(Check c) {
        Coverage coverage = coverages.get();
        Snapshot[] actual = {coverage.begin("run-a"), coverage.unknownStart("run-b")};
        long[] sequence = {0, 0};
        Status[] status = {Status.COMPLETE, Status.INCOMPLETE};
        Cause[] cause = {Cause.NONE, Cause.UNKNOWN_START};
        List<Snapshot> retained = new ArrayList<>();
        List<Snapshot> retainedCopies = new ArrayList<>();
        retain(actual[0], retained, retainedCopies);
        retain(actual[1], retained, retainedCopies);
        Cause[] causes = concreteCauses();
        Seeded random = new Seeded(0xC0A7E2L);
        boolean historyValid = true;
        boolean retainedStable = true;

        for (int step = 0; step < 2000; step++) {
            int which = random.nextInt(2);
            int other = 1 - which;
            Snapshot otherBefore = actual[other];
            int operation = random.nextInt(5);
            if (operation == 0) {
                actual[which] = coverage.commitDurable(actual[which], sequence[which] + 1);
                sequence[which]++;
            } else if (operation == 1) {
                Cause reported = causes[random.nextInt(causes.length)];
                actual[which] = coverage.markIncomplete(actual[which], reported);
                if (status[which] == Status.COMPLETE) {
                    status[which] = Status.INCOMPLETE;
                    cause[which] = reported;
                }
            } else if (operation == 2) {
                actual[which] = coverage.commitDurable(actual[which], sequence[which] + 2);
                if (status[which] == Status.COMPLETE) {
                    status[which] = Status.INCOMPLETE;
                    cause[which] = Cause.SEQUENCE_GAP;
                }
            } else if (operation == 3) {
                long invalidSequence = sequence[which] == 0 ? 0 : sequence[which];
                actual[which] = coverage.commitDurable(actual[which], invalidSequence);
                if (status[which] == Status.COMPLETE) {
                    status[which] = Status.INCOMPLETE;
                    cause[which] = Cause.INVALID_SEQUENCE;
                }
            } else {
                actual[which] = coverage.restore(actual[which]);
            }
            retain(actual[which], retained, retainedCopies);
            historyValid &= matches(actual[which], which == 0 ? "run-a" : "run-b",
                    sequence[which], status[which], cause[which]);
            historyValid &= actual[other] == otherBefore;
            for (int i = 0; i < retained.size(); i++) {
                Snapshot old = retained.get(i);
                Snapshot copy = retainedCopies.get(i);
                retainedStable &= old.equals(copy) && old.runId().equals(copy.runId())
                        && old.committedThrough() == copy.committedThrough()
                        && old.status() == copy.status() && old.cause() == copy.cause();
            }
        }
        c.that(historyValid, "L3:only-next-sequence-advances");
        c.that(retainedStable, "L7:snapshot-is-immutable");
    }

    private void retain(Snapshot snapshot, List<Snapshot> retained, List<Snapshot> copies) {
        retained.add(snapshot);
        copies.add(new Snapshot(snapshot.runId(), snapshot.committedThrough(), snapshot.status(), snapshot.cause()));
    }

    private boolean matches(Snapshot snapshot, String runId, long sequence, Status status, Cause cause) {
        return snapshot.runId().equals(runId) && snapshot.committedThrough() == sequence
                && snapshot.status() == status && snapshot.cause() == cause;
    }

    private Cause[] concreteCauses() {
        List<Cause> result = new ArrayList<>();
        for (Cause cause : Cause.values()) {
            if (cause != Cause.NONE) {
                result.add(cause);
            }
        }
        return result.toArray(new Cause[0]);
    }
}
