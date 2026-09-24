package mycelium.dimension_travel.v1.model;

import java.util.List;

/** Immutable snapshot of a player's carried stacks, in a stable order. */
public record Inventory(List<ItemToken> stacks) {
    public Inventory {
        stacks = List.copyOf(stacks);
    }
}
