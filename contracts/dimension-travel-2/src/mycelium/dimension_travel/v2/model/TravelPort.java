package mycelium.dimension_travel.v2.model;

import java.util.Optional;
import java.util.Set;

/** The world as a travel port: current position, current inventory, presence, and the mutating move. */
public interface TravelPort {
    Set<Trait> traits();

    Position where(PlayerId player);

    Inventory inventory(PlayerId player);

    boolean exists(PlayerId player, DimensionId dimension);

    /**
     * Moves the player. May throw {@link PortFailure} after having partially acted; the caller cannot assume
     * anything about state on that path beyond what {@link #where} and {@link #exists} report afterward.
     */
    void move(PlayerId player, Position target, Pose pose, TravelTx tx);

    /** @2: the traveller's open trip, if any. Durable: it survives a restart of the port's process. */
    Optional<Journal> journal(PlayerId player);

    /** @2: writes the open trip durably, before anything moves. Must register an undo with {@code tx}. */
    void writeJournal(PlayerId player, Journal journal, TravelTx tx);

    /** @2: strikes the open trip (the traveller is home). Must register an undo with {@code tx}. */
    void strikeJournal(PlayerId player, TravelTx tx);
}
