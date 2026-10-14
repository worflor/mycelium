package mycelium.storage_access.v1.model;

import java.util.Objects;

/** An amount of goods held: in a {@link Store}, in the declared unit; in a {@link NativeFluidStore}, in its own unit. */
public record Holding(Goods goods, long amount) {
    public Holding {
        Objects.requireNonNull(goods, "goods");
        if (amount < 1) {
            throw new IllegalArgumentException("a holding holds at least one unit");
        }
    }
}
