package mycelium.item_presentation.v1.harness;

import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Result;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The programs and the component catalog the harness compiles. */
final class Programs {
    private Programs() {
    }

    /** The components a tool_state tool and a dial read. {@code server_state} is server-private. */
    static final String CATALOG_JSON = "{\"schema\": 1, \"components\": {"
            + "\"example:tool_state\": {\"networkSynced\": true, \"fields\": {\"charge\": \"int\", \"wear\": \"int\", \"broken\": \"bool\"}},"
            + "\"example:server_state\": {\"networkSynced\": false, \"fields\": {\"deadline\": \"int\"}},"
            + "\"example:dial\": {\"networkSynced\": true, \"fields\": {\"mode\": \"string\", \"unused_level\": \"int\"}},"
            + "\"example:display_part\": {\"networkSynced\": true, \"value\": \"string\"},"
            + "\"minecraft:glider\": {\"networkSynced\": true, \"unit\": true}}}";

    static ComponentCatalog catalog() {
        return ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(CATALOG_JSON)).value();
    }

    private static final String BAND_INPUTS = "\"inputs\": {"
            + "\"charge\": {\"component\": \"example:tool_state\", \"field\": \"charge\", \"domain\": {\"int\": [0, 3]}},"
            + "\"wear\": {\"component\": \"example:tool_state\", \"field\": \"wear\", \"domain\": {\"int\": [0, 2]}},"
            + "\"broken\": {\"component\": \"example:tool_state\", \"field\": \"broken\", \"domain\": \"bool\"}}";
    static final String BROKEN = "{\"when\": {\"broken\": true}, \"model\": \"example:item/status_tool_broken\"}";
    static final String MENDED = "{\"when\": {\"charge\": {\"min\": 1}}, \"model\": \"example:item/status_tool_charged_{charge}_worn_{wear}\"}";
    static final String CRACKED = "{\"when\": {\"wear\": {\"min\": 1}}, \"model\": \"example:item/status_tool_worn_{wear}\"}";
    static final String PRIME = "{\"when\": {\"charge\": 3, \"wear\": 2, \"broken\": false}, \"model\": \"example:item/status_tool_aaa_prime\"}";

    /** The tool_state tool: charge 0 to 3, wear 0 to 2, and broken. Twelve selections and the base. */
    static String toolText(String... cases) {
        return "{\"item\": \"example:status_tool\", \"base\": \"example:item/status_tool_base\", " + BAND_INPUTS
                + ", \"cases\": [" + String.join(", ", cases) + "]}";
    }

    static Program tool() {
        return parse(toolText(BROKEN, MENDED, CRACKED));
    }

    /** The tool with a case added in front, whose model sorts before every other. */
    static Program toolExtended() {
        return parse(toolText(PRIME, BROKEN, MENDED, CRACKED));
    }

    /** The tool with a case taken away. */
    static Program toolReduced() {
        return parse(toolText(BROKEN, MENDED));
    }

    /** The tool written with its inputs and guards in another order: the same program. */
    static Program toolReordered() {
        return parse("{\"cases\": [" + BROKEN + ", {\"model\": \"example:item/status_tool_charged_{charge}_worn_{wear}\", \"when\": {\"charge\": {\"min\": 1}}}, "
                + CRACKED + "], \"base\": \"example:item/status_tool_base\", \"item\": \"example:status_tool\", \"inputs\": {"
                + "\"broken\": {\"domain\": \"bool\", \"field\": \"broken\", \"component\": \"example:tool_state\"},"
                + "\"wear\": {\"domain\": {\"int\": [0, 2]}, \"field\": \"wear\", \"component\": \"example:tool_state\"},"
                + "\"charge\": {\"domain\": {\"int\": [0, 3]}, \"field\": \"charge\", \"component\": \"example:tool_state\"}}}");
    }

    /** A dial: an enum, a unit component, and an input no case reads. The first matching case wins. */
    static Program dial() {
        return parse("{\"item\": \"example:dial\", \"base\": \"example:item/dial_base\", \"inputs\": {"
                + "\"mode\": {\"component\": \"example:dial\", \"field\": \"mode\", \"domain\": {\"enum\": [\"none\", \"idle\", \"active\"]}},"
                + "\"lit\": {\"component\": \"minecraft:glider\", \"domain\": \"present\"},"
                + "\"unused_level\": {\"component\": \"example:dial\", \"field\": \"unused_level\", \"domain\": {\"int\": [0, 1000000]}}},"
                + "\"cases\": ["
                + "{\"when\": {\"lit\": true, \"mode\": [\"idle\", \"active\"]}, \"model\": \"example:item/dial_lit_{mode}\"},"
                + "{\"when\": {\"mode\": \"active\"}, \"model\": \"example:item/dial_active\"},"
                + "{\"when\": {\"mode\": \"idle\"}, \"model\": \"example:item/dial_idle\"}]}");
    }

    /** A program with these inputs and cases, for the ones that test one thing. */
    static Program mini(String item, String base, String inputs, String cases) {
        return parse("{\"item\": \"" + item + "\", \"base\": \"" + base + "\", \"inputs\": {" + inputs + "}, \"cases\": [" + cases + "]}");
    }

    static Program parse(String text) {
        Result<Program> r = Program.parse(text);
        if (r instanceof Result.Failed<Program> failed) {
            throw new IllegalStateException("harness program is malformed: " + failed.diagnostics() + " in " + text);
        }
        return ((Result.Ok<Program>) r).value();
    }

    /** A stack whose components hold exactly these values. */
    static final class MapView implements ComponentView {
        private final Set<String> present = new HashSet<>();
        private final Map<String, Object> fields = new HashMap<>();

        /** A stack that reads as this tuple of the program's read inputs. */
        static MapView of(Program program, Map<String, Object> tuple) {
            MapView v = new MapView();
            for (Input i : program.readInputs()) {
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
}
