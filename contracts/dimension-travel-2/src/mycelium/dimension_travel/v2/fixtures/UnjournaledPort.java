package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Trait;

/** A port without the JOURNALED trait: every trip verb must say Unsupported and change nothing. */
public final class UnjournaledPort extends MemoryTravelPort {
    public UnjournaledPort() {
        super(new Store());
    }

    private UnjournaledPort(Store store) {
        super(store);
    }

    @Override
    public MemoryTravelPort restart() {
        return new UnjournaledPort(store);
    }

    @Override
    public java.util.Set<Trait> traits() {
        return java.util.Set.of(Trait.POSITION_EXACT, Trait.INVENTORY_PRESERVING);
    }
}
