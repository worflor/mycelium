package mycelium.world_replay.v1.fixtures;

import mycelium.world_replay.v1.model.ReplayTraversal;

/** Completes without asking the placement port to visit or skip anything. */
public final class NoOpReplayCursor implements ReplayTraversal {
    public NoOpReplayCursor(int[] runs, int volume, int paletteCount) {
    }

    @Override
    public Drain drain(long workUnitBudget, PlacementPort port) {
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
