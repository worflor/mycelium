package mycelium.flow.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.ResourceKey;

/**
 * A source that reports what it holds but gives none of it (a locked or momentarily busy store). The executor must
 * reject with {@code EXTRACTION_FAILED}: the source refused, not the destination, and nothing moved.
 */
public final class StuckSourceBackend extends MemoryBackend {
    public StuckSourceBackend(long capacityPerResource) {
        super(capacityPerResource);
    }

    @Override
    public long extract(ResourceKey resource, long amount, FlowTransaction tx) {
        return 0;
    }
}
