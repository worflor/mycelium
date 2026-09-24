package mycelium.world_copy.v1.model;

/** Decides whether a position that carries a block entity has its data cleared after the write. */
@FunctionalInterface
public interface SanitizePolicy {
    SanitizePolicy ALL = (pos, token) -> true;
    SanitizePolicy NONE = (pos, token) -> false;

    boolean clear(Pos pos, BlockToken token);
}
