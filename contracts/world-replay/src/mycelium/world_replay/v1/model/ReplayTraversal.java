package mycelium.world_replay.v1.model;

/** The observable laws of a resumable ordered RLE traversal. */
public interface ReplayTraversal {
    /** Result for one drain call. skippedRunPositions counts positions omitted by whole-run skips in this call. */
    record Drain(long workUnits, long skippedRunPositions, boolean complete) {
        public Drain {
            if (workUnits < 0 || skippedRunPositions < 0) {
                throw new IllegalArgumentException("Drain counts cannot be negative.");
            }
        }
    }

    /** Native effects supplied by the caller; the traversal owns ordering, budget and resume position. */
    interface PlacementPort {
        /** True skips the remaining positions in this RLE run as one work unit. */
        boolean skipRun(int paletteIndex);

        /** Visits one position and palette entry. A thrown exception leaves that position unadvanced. */
        void visit(int index, int paletteIndex);
    }

    /** Processes at most {@code workUnitBudget} units, resuming exactly where the previous drain stopped. */
    Drain drain(long workUnitBudget, PlacementPort port);

    /** Cumulative positions suppressed by whole-run skips, including completed work before a thrown callback. */
    long skippedRunPositions();

    /** Whether every RLE run has been skipped or visited. */
    boolean complete();
}
