package mycelium.dimension_travel.v2.model;

import java.util.Objects;

/** A trip settled after a restart: whose, which journal, and whether the traveller had to be brought home. */
public record RecoverReceipt(PlayerId player, Journal journal, boolean broughtHome) {
    public RecoverReceipt {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(journal, "journal");
    }
}
