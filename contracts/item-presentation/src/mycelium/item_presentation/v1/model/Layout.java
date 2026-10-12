package mycelium.item_presentation.v1.model;

/** Where the compiler puts what it writes, relative to a resources root. */
public final class Layout {
    private Layout() {
    }

    /** The item definition of an item (from the version that has them). */
    public static String definition(String item) {
        return "assets/" + Ids.namespace(item) + "/items/" + Ids.path(item) + ".json";
    }

    /** The item model file of an item: the model the legacy overrides live on. */
    public static String model(String item) {
        return "assets/" + Ids.namespace(item) + "/models/item/" + Ids.path(item) + ".json";
    }

    /** The model reference the item model file is, which no selection may also draw. */
    public static String itemModel(String item) {
        return Ids.namespace(item) + ":item/" + Ids.path(item);
    }

    /** The plan a platform adapter reads at run time. */
    public static String plan(String item) {
        return "META-INF/mycelium/presentation/" + Ids.namespace(item) + "/" + Ids.path(item) + ".plan.json";
    }
}
