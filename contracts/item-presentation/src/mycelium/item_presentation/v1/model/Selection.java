package mycelium.item_presentation.v1.model;

import java.util.Objects;

/**
 * The model a stack draws, as the plan decided it. {@code key} is the model reference (also the modern selector) and
 * {@code code} the legacy code; both are empty and zero for the base model. {@code inDomain} is false when the stack's
 * state was outside what the program declares and the base model was drawn instead.
 */
public record Selection(String model, String key, int code, boolean inDomain) {
    public Selection {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(key, "key");
        if ((code == 0) != key.isEmpty()) {
            throw new IllegalArgumentException("a selection has both a key and a code, or neither (the base model)");
        }
    }

    /** The base model: no case applied. */
    public static Selection base(String model, boolean inDomain) {
        return new Selection(model, "", 0, inDomain);
    }

    public boolean isBase() {
        return code == 0;
    }
}
