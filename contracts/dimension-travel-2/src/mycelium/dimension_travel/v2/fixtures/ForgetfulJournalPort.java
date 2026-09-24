package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.TravelTx;

/** Deliberately incorrect: keeps the journal in memory, not the durable store, so a crash forgets the trip (L3). */
public final class ForgetfulJournalPort extends MemoryTravelPort {
    public ForgetfulJournalPort() {
        super(new Store());
    }

    private ForgetfulJournalPort(Store store) {
        super(store);
    }

    @Override
    public MemoryTravelPort restart() {
        return new ForgetfulJournalPort(store);
    }

    private final java.util.Map<PlayerId, Journal> memoryOnly = new java.util.HashMap<>();

    @Override
    public java.util.Optional<Journal> journal(PlayerId player) {
        return java.util.Optional.ofNullable(memoryOnly.get(player));
    }

    @Override
    public void writeJournal(PlayerId player, Journal journal, TravelTx tx) {
        memoryOnly.put(player, journal);
        tx.registerUndo(() -> memoryOnly.remove(player));
    }

    @Override
    public void strikeJournal(PlayerId player, TravelTx tx) {
        Journal previous = memoryOnly.remove(player);
        tx.registerUndo(() -> {
            if (previous != null) {
                memoryOnly.put(player, previous);
            }
        });
    }
}
