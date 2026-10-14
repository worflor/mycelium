package mycelium.block_presentation.v1.harness;

import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The mosaics and the component catalog the harness compiles. */
final class Mosaics {
    private Mosaics() {
    }

    /** What a display reads. {@code vault} is server-private; {@code marker} is a unit component. */
    static final String CATALOG_JSON = "{\"schema\": 1, \"components\": {"
            + "\"example:display\": {\"networkSynced\": true, \"fields\": {\"north\": \"string\", \"east\": \"string\", \"south\": \"string\","
            + " \"west\": \"string\", \"lit\": \"bool\", \"glow\": \"int\"}},"
            + "\"example:vault\": {\"networkSynced\": false, \"fields\": {\"mark\": \"string\"}},"
            + "\"minecraft:marker\": {\"networkSynced\": true, \"unit\": true}}}";

    static ComponentCatalog catalog() {
        return ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(CATALOG_JSON)).value();
    }

    private static String side(String name) {
        return "\"" + name + "\": {\"component\": \"example:display\", \"field\": \"" + name
                + "\", \"domain\": {\"enum\": [\"none\", \"moon\", \"sun\", \"star\"]}}";
    }

    /** The core, four sides each turned a quarter further, a flame when lit, and a glow when lit and the north wears moon or sun. */
    static final String DISPLAY = "{\"block\": \"example:display\", \"inputs\": {" + side("north") + ", " + side("east") + ", " + side("south") + ", "
            + side("west") + ", \"lit\": {\"component\": \"example:display\", \"field\": \"lit\", \"domain\": \"bool\"},"
            + " \"glow\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [0, 3]}}},"
            + " \"parts\": ["
            + "{\"model\": \"example:block/display_core\"},"
            + "{\"model\": \"example:block/display_side_{north}\"},"
            + "{\"model\": \"example:block/display_side_{east}\", \"turn\": 90},"
            + "{\"model\": \"example:block/display_side_{south}\", \"turn\": 180},"
            + "{\"model\": \"example:block/display_side_{west}\", \"turn\": 270},"
            + "{\"when\": {\"lit\": true}, \"model\": \"example:block/display_flame\"},"
            + "{\"when\": {\"lit\": true, \"north\": [\"moon\", \"sun\"]}, \"model\": \"example:block/display_glow_{north}\"}]}";

    /** The same display written with its inputs, its guards and its keys in another order: the same mosaic. */
    static final String DISPLAY_REORDERED = "{\"parts\": ["
            + "{\"model\": \"example:block/display_core\"},"
            + "{\"model\": \"example:block/display_side_{north}\"},"
            + "{\"turn\": 90, \"model\": \"example:block/display_side_{east}\"},"
            + "{\"turn\": 180, \"model\": \"example:block/display_side_{south}\"},"
            + "{\"turn\": 270, \"model\": \"example:block/display_side_{west}\"},"
            + "{\"model\": \"example:block/display_flame\", \"when\": {\"lit\": true}},"
            + "{\"model\": \"example:block/display_glow_{north}\", \"when\": {\"north\": [\"moon\", \"sun\"], \"lit\": true}}],"
            + " \"inputs\": {\"glow\": {\"domain\": {\"int\": [0, 3]}, \"field\": \"glow\", \"component\": \"example:display\"},"
            + " \"lit\": {\"domain\": \"bool\", \"field\": \"lit\", \"component\": \"example:display\"}, " + side("west") + ", " + side("south") + ", "
            + side("east") + ", " + side("north") + "}, \"block\": \"example:display\"}";

    /** The display with its last two parts swapped: another picture. */
    static final String DISPLAY_SWAPPED = DISPLAY.replace(
            "{\"model\": \"example:block/display_core\"},{\"model\": \"example:block/display_side_{north}\"},",
            "{\"model\": \"example:block/display_side_{north}\"},{\"model\": \"example:block/display_core\"},");

    /** A panel: a core and four sides, each reading its own side's dress from {@code n} choices. */
    static String panel(int n) {
        StringBuilder choices = new StringBuilder("\"none\"");
        for (int i = 1; i < n; i++) {
            choices.append(", \"m").append(i).append('"');
        }
        StringBuilder b = new StringBuilder("{\"block\": \"example:panel\", \"inputs\": {");
        String[] sides = {"north", "east", "south", "west"};
        for (int i = 0; i < 4; i++) {
            b.append(i > 0 ? ", " : "").append('"').append(sides[i]).append("\": {\"component\": \"example:display\", \"field\": \"")
                    .append(sides[i]).append("\", \"domain\": {\"enum\": [").append(choices).append("]}}");
        }
        b.append("}, \"parts\": [{\"model\": \"example:block/panel_core\"}");
        for (int i = 0; i < 4; i++) {
            b.append(", {\"model\": \"example:block/panel_side_{").append(sides[i]).append("}\", \"turn\": ").append(i * 90).append('}');
        }
        return b.append("]}").toString();
    }

    static Mosaic parse(String text) {
        Result<Mosaic> r = Mosaic.parse(text);
        if (r instanceof Result.Failed<Mosaic> failed) {
            throw new IllegalStateException("harness mosaic is malformed: " + failed.diagnostics() + " in " + text);
        }
        return ((Result.Ok<Mosaic>) r).value();
    }

    /** A mosaic with these inputs and parts, for the ones that test one thing. */
    static Mosaic mini(String block, String inputs, String parts) {
        return parse("{\"block\": \"" + block + "\", \"inputs\": {" + inputs + "}, \"parts\": [" + parts + "]}");
    }

    /** A block whose data holds exactly these values. */
    static final class MapView implements ComponentView {
        private final Set<String> present = new HashSet<>();
        private final Map<String, Object> fields = new HashMap<>();

        /** Data that reads as this tuple of the mosaic's read inputs. */
        static MapView of(Mosaic mosaic, Map<String, Object> tuple) {
            MapView v = new MapView();
            for (Input i : mosaic.readInputs()) {
                Object value = tuple.get(i.name());
                if (i.domain() instanceof Domain.Present) {
                    if (Boolean.TRUE.equals(value)) {
                        v.present.add(i.component());
                    }
                } else {
                    v.fields.put(i.component() + "|" + i.field(), value);
                }
            }
            return v;
        }

        MapView with(String component, String field, Object value) {
            fields.put(component + "|" + field, value);
            return this;
        }

        @Override
        public boolean has(String componentId) {
            return present.contains(componentId);
        }

        @Override
        public Optional<Object> field(String componentId, String fieldPath) {
            return Optional.ofNullable(fields.get(componentId + "|" + fieldPath));
        }
    }

    static List<String> names(List<Input> inputs) {
        List<String> out = new ArrayList<>();
        for (Input i : inputs) {
            out.add(i.name());
        }
        return out;
    }
}
