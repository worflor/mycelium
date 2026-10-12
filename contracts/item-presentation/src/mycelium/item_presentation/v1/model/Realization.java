package mycelium.item_presentation.v1.model;

/**
 * The mechanism a Minecraft version draws an item's state with. Which version has which is data
 * ({@code eras/minecraft.json}), never code: this model holds no version number.
 */
public enum Realization {
    /** An item definition ({@code items/<id>.json}) selecting a model on the custom model data strings. */
    ITEM_DEFINITION("item-definition"),
    /** One item model with {@code overrides} on the float custom model data predicate. */
    MODEL_OVERRIDES("model-overrides"),
    /** No mechanism: compiling for this target is refused. */
    NONE("none");

    private final String id;

    Realization(String id) {
        this.id = id;
    }

    /** The name the era table and the plan use. */
    public String id() {
        return id;
    }

    /** The realization named {@code id}; an unknown name is an {@link IllegalArgumentException}. */
    public static Realization parse(String id) {
        for (Realization r : values()) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not a presentation realization (item-definition, model-overrides, none)");
    }
}
