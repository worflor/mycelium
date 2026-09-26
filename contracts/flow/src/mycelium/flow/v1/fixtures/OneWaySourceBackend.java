package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;

/**
 * A source that gives but will not take back: extraction works, every insert is refused. With a destination that admits
 * less than it promised, the remainder cannot be restored. The executor must not report that as an ordinary outcome
 * (DESIGN §7: an unconfirmed outcome is never a {@code Rejected}); it throws, and the caller's abort undoes everything.
 */
public final class OneWaySourceBackend extends MemoryBackend {
    public OneWaySourceBackend(long capacityPerResource) {
        super(capacityPerResource);
    }

    @Override
    public long insert(ResourceKey resource, long amount, FlowTransaction tx) {
        return 0;
    }
}
