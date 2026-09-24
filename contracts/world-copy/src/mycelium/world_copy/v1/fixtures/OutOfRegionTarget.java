package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;

/** Writes one extra position far outside any fixture region on every set. */
public final class OutOfRegionTarget extends ForwardingTarget {
    public static final Pos STRAY = new Pos(999, 0, 0);

    @Override
    public void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx) {
        delegate.set(pos, token, blockEntity, tx);
        delegate.set(STRAY, token, false, tx);
    }
}
