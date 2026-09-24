package mycelium.dimension_travel.v2.model;

import java.util.Objects;

/**
 * An open trip: who left, from where, carrying what. Written before the move and struck on the way home; if the
 * process dies between the two, the journal is what lets the next session settle the trip ({@link Trip#recover}).
 */
public record Journal(PlayerId player, Position origin, Inventory carried) {
    public Journal {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(carried, "carried");
    }
}
