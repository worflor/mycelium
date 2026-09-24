package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;
import mycelium.world_copy.v1.model.TargetPort;
import mycelium.world_copy.v1.model.Trait;

import java.util.Set;

/** Base for deliberately incorrect targets: forwards everything to a {@link MemoryTarget}. */
abstract class ForwardingTarget implements TargetPort {
    protected final MemoryTarget delegate = new MemoryTarget();

    @Override
    public Set<Trait> traits() {
        return delegate.traits();
    }

    @Override
    public void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx) {
        delegate.set(pos, token, blockEntity, tx);
    }

    @Override
    public void clearBlockEntity(Pos pos, CopyTx tx) {
        delegate.clearBlockEntity(pos, tx);
    }

    @Override
    public BlockToken token(Pos pos) {
        return delegate.token(pos);
    }

    @Override
    public boolean hasBlockEntity(Pos pos) {
        return delegate.hasBlockEntity(pos);
    }

    public MemoryTarget memory() {
        return delegate;
    }
}
