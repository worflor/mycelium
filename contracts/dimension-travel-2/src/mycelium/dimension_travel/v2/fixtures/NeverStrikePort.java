package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.TravelTx;

/** Deliberately incorrect: never strikes the journal, so a traveller who came home is recovered again next session (L5). */
public final class NeverStrikePort extends MemoryTravelPort {
    public NeverStrikePort() {
        super(new Store());
    }

    private NeverStrikePort(Store store) {
        super(store);
    }

    @Override
    public MemoryTravelPort restart() {
        return new NeverStrikePort(store);
    }

    @Override
    public void strikeJournal(PlayerId player, TravelTx tx) {
        // forgets
    }
}
