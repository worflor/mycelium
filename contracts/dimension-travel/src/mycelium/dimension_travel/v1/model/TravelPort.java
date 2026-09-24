package mycelium.dimension_travel.v1.model;

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
}
