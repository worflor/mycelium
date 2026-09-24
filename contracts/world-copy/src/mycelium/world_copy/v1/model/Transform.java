package mycelium.world_copy.v1.model;

/** Maps a source token at a position to the token written at the same position. Must be pure. */
@FunctionalInterface
public interface Transform {
    Transform IDENTITY = (pos, source) -> source;

    BlockToken apply(Pos pos, BlockToken source);
}
