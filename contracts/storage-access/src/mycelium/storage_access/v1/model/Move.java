package mycelium.storage_access.v1.model;

import java.util.Objects;

/** A stroke's request: goods from one store to another, at most {@code amount} in the declared unit of their kind. */
public record Move(StoreAddress from, StoreAddress to, Goods goods, long amount) {
    public Move {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(goods, "goods");
    }
}
