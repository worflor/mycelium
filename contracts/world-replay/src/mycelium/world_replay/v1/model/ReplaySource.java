package mycelium.world_replay.v1.model;

/** Creates independent traversal state over an immutable replay program. */
@FunctionalInterface
public interface ReplaySource {
    ReplayTraversal cursor();
}
