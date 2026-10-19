package mycelium.world_replay.v1.harness;

import mycelium.harness.Check;
import mycelium.world_replay.v1.model.ReplayCursor;
import mycelium.world_replay.v1.model.ReplayProgram;
import mycelium.world_replay.v1.model.ReplaySource;
import mycelium.world_replay.v1.model.ReplayTraversal;

import java.util.ArrayList;
import java.util.List;

/** world.replay@1: ordered, resumable RLE traversal under a per-drain work budget (SPEC.md). */
public final class ReplayCursorContractTests {
    @FunctionalInterface
    public interface Factory {
        ReplayTraversal create(int[] runs, int volume, int paletteCount);
    }

    private final Factory cursors;

    public ReplayCursorContractTests(Factory cursors) {
        this.cursors = cursors;
    }

    public static Check reference() {
        Check check = new ReplayCursorContractTests(ReplayCursor::new).run();
        programSourceConformance(check, new ReplayProgram(new int[]{1, 2, 0}, 4, 1));
        return check;
    }

    /** R7: one immutable source must yield cursors with separate resume positions. */
    public static void programSourceConformance(Check check, ReplaySource source) {
        ReplayTraversal first = source.cursor();
        ReplayTraversal second = source.cursor();
        List<Integer> firstVisits = new ArrayList<>();
        List<Integer> secondVisits = new ArrayList<>();
        first.drain(1, port(firstVisits, ignored -> false));
        second.drain(1, port(secondVisits, ignored -> false));
        first.drain(1, port(firstVisits, ignored -> false));
        ReplayTraversal third = source.cursor();
        List<Integer> thirdVisits = new ArrayList<>();
        third.drain(1, port(thirdVisits, ignored -> false));
        second.drain(1, port(secondVisits, ignored -> false));
        boolean independent = first.complete() && second.complete() && !third.complete()
                && firstVisits.equals(List.of(1, 2)) && secondVisits.equals(List.of(1, 2))
                && thirdVisits.equals(List.of(1));
        third.drain(1, port(thirdVisits, ignored -> false));
        independent &= third.complete() && thirdVisits.equals(List.of(1, 2));
        check.that(independent,
                "R7:independent-cursors");

        if (source instanceof ReplayProgram program) {
            int[] snapshot = program.copyRuns();
            snapshot[0] = 0;
            List<Integer> afterSnapshotMutation = new ArrayList<>();
            program.cursor().drain(1, port(afterSnapshotMutation, ignored -> false));
            check.that(afterSnapshotMutation.equals(List.of(1)), "R6:input-is-copied");
        }
    }

    public Check run() {
        Check check = new Check();
        r1OrderedAcrossDrains(check);
        r2BudgetBoundsWork(check);
        r3WholeRunSkip(check);
        r4FailedVisitCanRetry(check);
        r5MalformedRunsRefused(check);
        r6OwnsInput(check);
        seededOracle(check);
        return check;
    }

    private void r1OrderedAcrossDrains(Check check) {
        ReplayTraversal cursor = cursors.create(new int[]{1, 2, 0, 5, 2, 1}, 8, 2);
        List<Integer> visited = new ArrayList<>();
        ReplayTraversal.PlacementPort port = port(visited, ignored -> false);
        int drains = 0;
        while (!cursor.complete() && drains++ < 8) {
            cursor.drain(2, port);
        }
        boolean ordered = cursor.complete() && visited.equals(List.of(1, 2, 5, 6));

        ReplayTraversal empty = cursors.create(new int[0], 8, 2);
        ReplayTraversal.Drain emptyDrain = empty.drain(0, port(new ArrayList<>(), ignored -> false));
        check.that(ordered && empty.complete() && emptyDrain.workUnits() == 0,
                "R1:ordered-visits");
    }

    private void r2BudgetBoundsWork(Check check) {
        ReplayTraversal cursor = cursors.create(new int[]{0, 4, 0}, 4, 1);
        List<Integer> visited = new ArrayList<>();
        ReplayTraversal.Drain drain = cursor.drain(1, port(visited, ignored -> false));
        check.that(drain.workUnits() == 1 && visited.equals(List.of(0)) && !cursor.complete(),
                "R2:work-within-budget");
    }

    private void r3WholeRunSkip(Check check) {
        ReplayTraversal cursor = cursors.create(new int[]{0, 4, 0}, 4, 1);
        List<Integer> visited = new ArrayList<>();
        ReplayTraversal.Drain drain = cursor.drain(1, port(visited, ignored -> true));
        check.that(drain.workUnits() == 1 && drain.skippedRunPositions() == 4
                        && cursor.skippedRunPositions() == 4 && cursor.complete() && visited.isEmpty(),
                "R3:whole-run-skip-costs-one");
    }

    private void r4FailedVisitCanRetry(Check check) {
        ReplayTraversal cursor = cursors.create(new int[]{0, 1, 0, 4, 2, 1}, 6, 2);
        List<Integer> visited = new ArrayList<>();
        boolean[] throwOnce = {true};
        ReplayTraversal.PlacementPort throwing = new ReplayTraversal.PlacementPort() {
            @Override
            public boolean skipRun(int paletteIndex) {
                return paletteIndex == 0;
            }

            @Override
            public void visit(int index, int paletteIndex) {
                visited.add(index);
                if (index == 4 && throwOnce[0]) {
                    throwOnce[0] = false;
                    throw new IllegalStateException("injected failed visit");
                }
            }
        };
        boolean threw = false;
        try {
            cursor.drain(3, throwing);
        } catch (IllegalStateException expected) {
            threw = true;
        }
        boolean partial = threw && !cursor.complete() && cursor.skippedRunPositions() == 1;
        cursor.drain(2, throwing);
        boolean retryVisit = partial && cursor.complete()
                && visited.stream().filter(index -> index == 4).count() == 2
                && visited.stream().filter(index -> index == 5).count() == 1;

        ReplayTraversal skipCursor = cursors.create(new int[]{0, 2, 0}, 2, 1);
        boolean[] throwSkipOnce = {true};
        ReplayTraversal.PlacementPort throwingSkip = new ReplayTraversal.PlacementPort() {
            @Override
            public boolean skipRun(int paletteIndex) {
                if (throwSkipOnce[0]) {
                    throwSkipOnce[0] = false;
                    throw new IllegalStateException("injected failed skip");
                }
                return true;
            }

            @Override
            public void visit(int index, int paletteIndex) {
                throw new AssertionError("A skipped run must not visit cells.");
            }
        };
        boolean skipThrew = false;
        try {
            skipCursor.drain(1, throwingSkip);
        } catch (IllegalStateException expected) {
            skipThrew = true;
        }
        boolean skipStayed = skipThrew && !skipCursor.complete() && skipCursor.skippedRunPositions() == 0;
        skipCursor.drain(1, throwingSkip);

        ReplayTraversal partialRun = cursors.create(new int[]{0, 2, 0}, 2, 1);
        List<Integer> partialVisits = new ArrayList<>();
        partialRun.drain(1, port(partialVisits, ignored -> false));
        ReplayTraversal.Drain skippedRemainder = partialRun.drain(1, port(partialVisits, ignored -> true));
        boolean remainder = partialRun.complete() && partialVisits.equals(List.of(0))
                && skippedRemainder.skippedRunPositions() == 1;

        check.that(retryVisit && skipStayed && skipCursor.complete() && skipCursor.skippedRunPositions() == 2
                        && remainder,
                "R4:failed-visit-is-retried");
    }

    private void r5MalformedRunsRefused(Check check) {
        int[][] malformed = {
                {3, 2, 0},       // outside the volume
                {-1, 1, 0},      // negative start
                {0, 0, 0},       // empty run
                {0, -1, 0},      // negative length
                {0, 2, 0, 1, 1, 0}, // overlap
                {0, 1, 1},       // palette index out of range
                {Integer.MAX_VALUE, 2, 0}, // end arithmetic overflow
                {0, 1}           // incomplete triple
        };
        boolean refusedAll = true;
        for (int[] runs : malformed) {
            try {
                cursors.create(runs, 4, 1);
                refusedAll = false;
            } catch (RuntimeException expected) {
                // Every malformed operation is rejected at construction.
            }
        }
        try {
            cursors.create(new int[0], 0, 1);
            refusedAll = false;
        } catch (RuntimeException expected) {
            // The operation volume must be positive, including for an empty operation.
        }
        try {
            cursors.create(new int[0], 4, 0);
            refusedAll = false;
        } catch (RuntimeException expected) {
            // A palette cannot be empty for a replay payload.
        }
        check.that(refusedAll, "R5:malformed-run-rejected");
    }

    private void r6OwnsInput(Check check) {
        int[] input = {1, 2, 0};
        ReplayTraversal cursor = cursors.create(input, 4, 1);
        input[0] = 0;
        input[1] = 0;
        List<Integer> visited = new ArrayList<>();
        cursor.drain(4, port(visited, ignored -> false));
        check.that(visited.equals(List.of(1, 2)), "R6:input-is-copied");
    }

    private void seededOracle(Check check) {
        java.util.Random random = new java.util.Random(0x52504c59L);
        boolean ordered = true;
        boolean budgeted = true;
        for (int sample = 0; sample < 32 && (ordered || budgeted); sample++) {
            int volume = 24;
            List<Integer> expected = new ArrayList<>();
            List<Integer> flatRuns = new ArrayList<>();
            int at = 0;
            while (at < volume) {
                at += random.nextInt(3);
                if (at >= volume) break;
                int length = 1 + random.nextInt(Math.min(4, volume - at));
                int palette = random.nextInt(3);
                flatRuns.add(at);
                flatRuns.add(length);
                flatRuns.add(palette);
                for (int index = at; index < at + length; index++) expected.add(index * 4 + palette);
                at += length;
            }
            int[] runs = flatRuns.stream().mapToInt(Integer::intValue).toArray();
            ReplayTraversal cursor = cursors.create(runs, volume, 3);
            List<Integer> observed = new ArrayList<>();
            ReplayTraversal.PlacementPort port = new ReplayTraversal.PlacementPort() {
                @Override
                public boolean skipRun(int paletteIndex) {
                    return false;
                }

                @Override
                public void visit(int index, int paletteIndex) {
                    observed.add(index * 4 + paletteIndex);
                }
            };
            int drains = 0;
            while (!cursor.complete() && drains++ < 100) {
                long budget = random.nextInt(6);
                int beforeCount = observed.size();
                ReplayTraversal.Drain result = cursor.drain(budget, port);
                int visited = observed.size() - beforeCount;
                if (result.workUnits() > budget || result.workUnits() != visited
                        || (budget == 0 && visited != 0)) budgeted = false;
            }
            ordered &= cursor.complete() && observed.equals(expected);
        }
        check.that(ordered, "R1:ordered-visits");
        check.that(budgeted, "R2:work-within-budget");
    }

    private static ReplayTraversal.PlacementPort port(List<Integer> visited,
                                                      java.util.function.IntPredicate skipRun) {
        return new ReplayTraversal.PlacementPort() {
            @Override
            public boolean skipRun(int paletteIndex) {
                return skipRun.test(paletteIndex);
            }

            @Override
            public void visit(int index, int paletteIndex) {
                visited.add(index);
            }
        };
    }
}
