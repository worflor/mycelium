package mycelium.world_replay.v1.model;

import java.util.Objects;

/**
 * world.replay@1's reference cursor (SPEC.md): one operation's immutable sequence of start/length/palette triples,
 * resumed under a caller-supplied work-unit budget.
 */
public final class ReplayCursor implements ReplayTraversal {
    private final ReplayProgram program;
    private int runIndex;
    private int runOffset;
    private long skippedRunPositions;

    /**
     * Copies and validates one operation's RLE data. Non-overlapping positive runs bound the copied triple count by
     * the operation volume; callers may construct a new cursor for the next operation after this one completes.
     */
    public ReplayCursor(int[] runs, int volume, int paletteCount) {
        this(new ReplayProgram(runs, volume, paletteCount));
    }

    ReplayCursor(ReplayProgram program) {
        this.program = program;
    }

    @Override
    public Drain drain(long workUnitBudget, PlacementPort port) {
        if (workUnitBudget < 0) {
            throw new IllegalArgumentException("Replay work-unit budget cannot be negative.");
        }
        Objects.requireNonNull(port, "port");
        long skippedBefore = skippedRunPositions;
        long workUnits = 0;
        while (workUnits < workUnitBudget && !complete()) {
            int start = program.start(runIndex);
            int length = program.length(runIndex);
            int paletteIndex = program.paletteIndex(runIndex);
            if (port.skipRun(paletteIndex)) {
                skippedRunPositions += length - runOffset;
                workUnits++;
                nextRun();
                continue;
            }
            while (workUnits < workUnitBudget && runOffset < length) {
                port.visit(start + runOffset, paletteIndex);
                runOffset++;
                workUnits++;
            }
            if (runOffset == length) {
                nextRun();
            }
        }
        return new Drain(workUnits, skippedRunPositions - skippedBefore, complete());
    }

    @Override
    public long skippedRunPositions() {
        return skippedRunPositions;
    }

    @Override
    public boolean complete() {
        return runIndex == program.runCount();
    }

    private void nextRun() {
        runIndex++;
        runOffset = 0;
    }

}
