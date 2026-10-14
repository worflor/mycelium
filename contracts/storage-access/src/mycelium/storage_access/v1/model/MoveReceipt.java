package mycelium.storage_access.v1.model;

import java.util.Objects;

/** What a move committed: {@code moved} of the goods left {@code from} and arrived at {@code to}. Zero is never a receipt. */
public record MoveReceipt(StoreAddress from, StoreAddress to, Goods goods, long requested, long moved) {
    public MoveReceipt {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(goods, "goods");
        if (requested < 1) {
            throw new IllegalArgumentException("requested must be >= 1");
        }
        if (moved < 1) {
            throw new IllegalArgumentException("moved must be >= 1; moving nothing is a rejection");
        }
        if (moved > requested) {
            throw new IllegalArgumentException("moved must not exceed requested");
        }
    }
}
