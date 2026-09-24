package mycelium.world_copy.v1.model;

/** Read-only view of the source dimension. Reads must not mutate anything. */
public interface SourceView {
    BlockToken token(Pos pos);

    boolean hasBlockEntity(Pos pos);
}
