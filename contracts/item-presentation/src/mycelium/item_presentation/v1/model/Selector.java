package mycelium.item_presentation.v1.model;

/**
 * What a platform adapter writes onto a stack so the era's own machinery draws the selected model: nothing for the base
 * model, the key as the first custom model data string (item definitions), or the code as the custom model data number
 * (model overrides).
 */
public sealed interface Selector {
    /** Write nothing; the base model draws. */
    record None() implements Selector {
    }

    /** Write this as custom model data string 0. */
    record Key(String key) implements Selector {
    }

    /** Write this as the custom model data number. */
    record Code(int code) implements Selector {
    }
}
