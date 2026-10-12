package mycelium.item_presentation.v1.model;

/** What a component field holds, as far as presentation reads it: a flag, a whole number or a text. */
public enum FieldKind {
    BOOL("bool"),
    INT("int"),
    TEXT("string");

    private final String id;

    FieldKind(String id) {
        this.id = id;
    }

    /** The name the catalog uses. */
    public String id() {
        return id;
    }

    /** The kind of an input value ({@code Boolean}, {@code Long} or {@code String}), or {@code null} for any other. */
    public static FieldKind of(Object value) {
        if (value instanceof Boolean) {
            return BOOL;
        }
        if (value instanceof Long) {
            return INT;
        }
        if (value instanceof String) {
            return TEXT;
        }
        return null;
    }

    public static FieldKind parse(String id) {
        for (FieldKind k : values()) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        throw new IllegalArgumentException("'" + id + "' is not a field kind (bool, int, string)");
    }
}
