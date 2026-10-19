package mycelium.world_replay.v1.fixtures;

import mycelium.world_replay.v1.model.ReplaySource;
import mycelium.world_replay.v1.model.ReplayTraversal;

/** Broken source mutant that returns one shared mutable cursor for every request. */
public final class SharedPositionReplaySource implements ReplaySource {
    private final ReplayTraversal shared;

    public SharedPositionReplaySource(ReplayTraversal shared) {
        this.shared = shared;
    }

    @Override
    public ReplayTraversal cursor() {
        return shared;
    }
}
