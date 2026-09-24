package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;

/** Declares the traits, never writes. Must be rejected for failing the positive obligation, not for damage. */
public final class NoOpTarget extends ForwardingTarget {
    @Override
    public void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx) {
        // intentionally nothing
    }

    @Override
    public void clearBlockEntity(Pos pos, CopyTx tx) {
        // intentionally nothing
    }
}
