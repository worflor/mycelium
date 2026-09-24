package mycelium.world_copy.v1.model;

import java.util.Set;

/** The target dimension as a write port. Mutation only inside a transaction. */
public interface TargetPort {
    Set<Trait> traits();

    /**
     * Places {@code token} at {@code pos}. {@code blockEntity} carries whether the source position had a
     * block entity; a faithful target preserves that presence (its data is opaque here).
     */
    void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx);

    /** Removes block-entity data (contents) at {@code pos}, keeping the block token. */
    void clearBlockEntity(Pos pos, CopyTx tx);

    BlockToken token(Pos pos);

    boolean hasBlockEntity(Pos pos);
}
