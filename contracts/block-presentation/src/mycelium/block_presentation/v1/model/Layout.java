package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.Ids;

/** Where the compiler puts what it writes, relative to a resources root. */
public final class Layout {
    private Layout() {
    }

    /** The plan a platform adapter reads at run time. */
    public static String plan(String block) {
        return "META-INF/mycelium/presentation/" + Ids.namespace(block) + "/" + Ids.path(block) + ".mosaic.plan.json";
    }
}
