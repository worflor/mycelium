package mycelium.world_replay.v1.fixtures;

import mycelium.world_replay.v1.model.ReplayTraversal;

import java.util.Objects;

/** Deliberately broken cursor algorithms used by the normative rejection matrix. */
public final class ReplayCursorVariants {
    private ReplayCursorVariants() {
    }

    public static ReplayTraversal reordered(int[] runs, int volume, int paletteCount) {
        return new ReorderedCursor(runs);
    }

    public static ReplayTraversal budgetIgnored(int[] runs, int volume, int paletteCount) {
        return new BudgetIgnoringCursor(runs);
    }

    public static ReplayTraversal perCellSkip(int[] runs, int volume, int paletteCount) {
        return new PerCellSkipCursor(runs);
    }

    public static ReplayTraversal advancesOnFailure(int[] runs, int volume, int paletteCount) {
        return new AdvancesOnFailureCursor(runs, volume, paletteCount);
    }

    public static ReplayTraversal trustsMalformedData(int[] runs, int volume, int paletteCount) {
        return new TrustsMalformedDataCursor();
    }

    public static ReplayTraversal aliasesInput(int[] runs, int volume, int paletteCount) {
        return new AliasingCursor(runs);
    }

    private abstract static class State implements ReplayTraversal {
        final int[] runs;
        int run;
        int offset;
        long skipped;

        State(int[] runs) {
            this.runs = runs.clone();
        }

        @Override
        public long skippedRunPositions() {
            return skipped;
        }

        @Override
        public boolean complete() {
            return run == runs.length / 3;
        }

        void nextRun() {
            run++;
            offset = 0;
        }
    }

    private static final class ReorderedCursor extends State {
        ReorderedCursor(int[] runs) {
            super(runs);
        }

        @Override
        public Drain drain(long budget, PlacementPort port) {
            long before = skipped;
            long work = 0;
            while (work < budget && !complete()) {
                int tuple = run * 3;
                int start = runs[tuple], length = runs[tuple + 1], palette = runs[tuple + 2];
                if (length <= offset) {
                    run++;
                    offset = 0;
                    continue;
                }
                if (port.skipRun(palette)) {
                    skipped += length - offset;
                    work++;
                    nextRun();
                    continue;
                }
                while (work < budget && offset < length) {
                    port.visit(start + length - offset - 1, palette);
                    offset++;
                    work++;
                }
                if (offset == length) nextRun();
            }
            return new Drain(work, skipped - before, complete());
        }
    }

    private static final class BudgetIgnoringCursor extends State {
        BudgetIgnoringCursor(int[] runs) {
            super(runs);
        }

        @Override
        public Drain drain(long budget, PlacementPort port) {
            long before = skipped;
            long work = 0;
            while (!complete()) {
                int tuple = run * 3;
                int start = runs[tuple], length = runs[tuple + 1], palette = runs[tuple + 2];
                if (port.skipRun(palette)) {
                    skipped += length - offset;
                    work++;
                    nextRun();
                    continue;
                }
                while (offset < length) {
                    port.visit(start + offset, palette);
                    offset++;
                    work++;
                }
                nextRun();
            }
            return new Drain(work, skipped - before, complete());
        }
    }

    private static final class PerCellSkipCursor extends State {
        PerCellSkipCursor(int[] runs) {
            super(runs);
        }

        @Override
        public Drain drain(long budget, PlacementPort port) {
            long before = skipped;
            long work = 0;
            while (work < budget && !complete()) {
                int tuple = run * 3;
                int start = runs[tuple], length = runs[tuple + 1], palette = runs[tuple + 2];
                if (port.skipRun(palette)) {
                    skipped++;
                    offset++;
                    work++;
                    if (offset == length) nextRun();
                } else {
                    port.visit(start + offset, palette);
                    offset++;
                    work++;
                    if (offset == length) nextRun();
                }
            }
            return new Drain(work, skipped - before, complete());
        }
    }

    private static final class AdvancesOnFailureCursor implements ReplayTraversal {
        private final ReplayTraversal delegate;

        AdvancesOnFailureCursor(int[] runs, int volume, int paletteCount) {
            delegate = new mycelium.world_replay.v1.model.ReplayCursor(runs, volume, paletteCount);
        }

        @Override
        public Drain drain(long budget, PlacementPort port) {
            boolean[] failedVisit = {false};
            PlacementPort forwarding = new PlacementPort() {
                @Override
                public boolean skipRun(int paletteIndex) {
                    return port.skipRun(paletteIndex);
                }

                @Override
                public void visit(int index, int paletteIndex) {
                    try {
                        port.visit(index, paletteIndex);
                    } catch (RuntimeException failure) {
                        failedVisit[0] = true;
                        throw failure;
                    }
                }
            };
            try {
                return delegate.drain(budget, forwarding);
            } catch (RuntimeException failure) {
                if (failedVisit[0]) {
                    delegate.drain(1, new PlacementPort() {
                        @Override
                        public boolean skipRun(int paletteIndex) {
                            return false;
                        }

                        @Override
                        public void visit(int index, int paletteIndex) {
                            // The bad cursor consumes the failed position before retrying.
                        }
                    });
                }
                throw failure;
            }
        }

        @Override
        public long skippedRunPositions() {
            return delegate.skippedRunPositions();
        }

        @Override
        public boolean complete() {
            return delegate.complete();
        }
    }

    private static final class TrustsMalformedDataCursor implements ReplayTraversal {
        @Override
        public Drain drain(long budget, PlacementPort port) {
            Objects.requireNonNull(port, "port");
            return new Drain(0, 0, true);
        }

        @Override
        public long skippedRunPositions() {
            return 0;
        }

        @Override
        public boolean complete() {
            return true;
        }
    }

    private static final class AliasingCursor implements ReplayTraversal {
        private final int[] runs;
        private int run;
        private int offset;

        AliasingCursor(int[] runs) {
            this.runs = runs;
        }

        @Override
        public Drain drain(long budget, PlacementPort port) {
            long work = 0;
            while (work < budget && !complete()) {
                int tuple = run * 3;
                int start = runs[tuple], length = runs[tuple + 1], palette = runs[tuple + 2];
                if (port.skipRun(palette)) {
                    work++;
                    run++;
                    offset = 0;
                } else {
                    port.visit(start + offset, palette);
                    offset++;
                    work++;
                    if (offset == length) {
                        run++;
                        offset = 0;
                    }
                }
            }
            return new Drain(work, 0, complete());
        }

        @Override
        public long skippedRunPositions() {
            return 0;
        }

        @Override
        public boolean complete() {
            return run == runs.length / 3;
        }
    }
}
