package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.TravelTx;

/** Deliberately incorrect: claims JOURNALED but never writes anything (P1). */
public final class NoOpJournalPort extends MemoryTravelPort {
    public NoOpJournalPort() {
        super(new Store());
    }

    private NoOpJournalPort(Store store) {
        super(store);
    }

    @Override
    public MemoryTravelPort restart() {
        return new NoOpJournalPort(store);
    }

    @Override
    public void writeJournal(PlayerId player, Journal journal, TravelTx tx) {
        // claims the trait, keeps nothing
    }
}
