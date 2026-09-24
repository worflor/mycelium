package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;

/** Drops block-entity presence on every write, silently: signs lose text, chests lose their type data. */
public final class LossyTarget extends ForwardingTarget {
    @Override
    public void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx) {
        delegate.set(pos, token, false, tx);
    }
}
