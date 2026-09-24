package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;

/** Ignores clear requests: copied containers keep their contents. The duplication vector. */
public final class LeakyTarget extends ForwardingTarget {
    @Override
    public void clearBlockEntity(Pos pos, CopyTx tx) {
        // intentionally keeps the block entity
    }
}
