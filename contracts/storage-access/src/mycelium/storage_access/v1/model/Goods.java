package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.ResourceKey;

import java.util.Objects;

/**
 * A variant of goods: its kind and an opaque identity token, {@code inventory.transfer@1}'s {@link ResourceKey} (an item
 * with its components, a fluid with its data). Equality is identity: two stacks of one item with different components are
 * different goods. The token is the seam's; the contract never reads it.
 */
public record Goods(Kind kind, ResourceKey variant) {
    public Goods {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(variant, "variant");
    }

    public static Goods item(String token) {
        return new Goods(Kind.ITEM, new ResourceKey(token));
    }

    public static Goods fluid(String token) {
        return new Goods(Kind.FLUID, new ResourceKey(token));
    }
}
