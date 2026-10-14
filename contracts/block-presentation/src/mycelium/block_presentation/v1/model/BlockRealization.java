package mycelium.block_presentation.v1.model;

/**
 * The mechanism a Minecraft version has to draw a block from several models chosen by the state of its block entity. Which version
 * has which is data ({@code eras/minecraft.json}, the {@code block.presentation} table of {@code realizations}), never code: this model holds no version number.
 */
public enum BlockRealization {
    /** The platform's baked model can be replaced by one that composes models, fed per block from the block entity's data. */
    DYNAMIC_MODEL("dynamic-model"),
    /** No such mechanism: compiling for this target is refused. */
    NONE("none");

    private final String id;

    BlockRealization(String id) {
        this.id = id;
    }

    /** The name the era table and the plan use. */
    public String id() {
        return id;
    }

    /** The realization named {@code id}; an unknown name is an {@link IllegalArgumentException}. */
    public static BlockRealization parse(String id) {
        for (BlockRealization r : values()) {
            if (r.id.equals(id)) {
                return r;
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not a block presentation realization (dynamic-model, none)");
    }
}
