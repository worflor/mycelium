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

    /** The components a tool_state band and a spool read. {@code server_state} is server-private. */
    static final String CATALOG_JSON = "{\"schema\": 1, \"components\": {"
            + "\"example:tool_state\": {\"networkSynced\": true, \"fields\": {\"seams\": \"int\", \"cracks\": \"int\", \"broken\": \"bool\"}},"
            + "\"example:server_state\": {\"networkSynced\": false, \"fields\": {\"deadline\": \"int\"}},"
            + "\"example:spool\": {\"networkSynced\": true, \"fields\": {\"thread\": \"string\", \"tension\": \"int\"}},"
            + "\"minecraft:glider\": {\"networkSynced\": true, \"unit\": true}}}";

    static ComponentCatalog catalog() {
        return ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(CATALOG_JSON)).value();
    }

    private static final String BAND_INPUTS = "\"inputs\": {"
            + "\"seams\": {\"component\": \"example:tool_state\", \"field\": \"seams\", \"domain\": {\"int\": [0, 3]}},"
            + "\"cracks\": {\"component\": \"example:tool_state\", \"field\": \"cracks\", \"domain\": {\"int\": [0, 2]}},"
            + "\"broken\": {\"component\": \"example:tool_state\", \"field\": \"broken\", \"domain\": \"bool\"}}";
    static final String BROKEN = "{\"when\": {\"broken\": true}, \"model\": \"example:item/status_tool_broken\"}";
    static final String MENDED = "{\"when\": {\"seams\": {\"min\": 1}}, \"model\": \"example:item/status_tool_mended_{seams}_cracked_{cracks}\"}";
    static final String CRACKED = "{\"when\": {\"cracks\": {\"min\": 1}}, \"model\": \"example:item/status_tool_cracked_{cracks}\"}";
    static final String PRIME = "{\"when\": {\"seams\": 3, \"cracks\": 2, \"broken\": false}, \"model\": \"example:item/status_tool_aaa_prime\"}";

    /** The tool_state band: seams 0 to 3, cracks 0 to 2, and broken. Twelve selections and the base. */
    static String bandText(String... cases) {
        return "{\"item\": \"example:status_tool\", \"base\": \"example:item/status_tool_base\", " + BAND_INPUTS
                + ", \"cases\": [" + String.join(", ", cases) + "]}";
    }

    static Program band() {
        return parse(bandText(BROKEN, MENDED, CRACKED));
    }

    /** The band with a case added in front, whose model sorts before every other. */
    static Program bandExtended() {
        return parse(bandText(PRIME, BROKEN, MENDED, CRACKED));
    }

    /** The band with a case taken away. */
    static Program bandReduced() {
        return parse(bandText(BROKEN, MENDED));
    }

    /** The band written with its inputs and guards in another order: the same program. */
    static Program bandReordered() {
        return parse("{\"cases\": [" + BROKEN + ", {\"model\": \"example:item/status_tool_mended_{seams}_cracked_{cracks}\", \"when\": {\"seams\": {\"min\": 1}}}, "
                + CRACKED + "], \"base\": \"example:item/status_tool_base\", \"item\": \"example:status_tool\", \"inputs\": {"
                + "\"broken\": {\"domain\": \"bool\", \"field\": \"broken\", \"component\": \"example:tool_state\"},"
                + "\"cracks\": {\"domain\": {\"int\": [0, 2]}, \"field\": \"cracks\", \"component\": \"example:tool_state\"},"
                + "\"seams\": {\"domain\": {\"int\": [0, 3]}, \"field\": \"seams\", \"component\": \"example:tool_state\"}}}");
    }

    /** A spool: an enum, a unit component, and an input no case reads. The first matching case wins. */
    static Program spool() {
        return parse("{\"item\": \"example:spool\", \"base\": \"example:item/spool_base\", \"inputs\": {"
                + "\"thread\": {\"component\": \"example:spool\", \"field\": \"thread\", \"domain\": {\"enum\": [\"none\", \"loose\", \"taut\"]}},"
                + "\"lit\": {\"component\": \"minecraft:glider\", \"domain\": \"present\"},"
                + "\"tension\": {\"component\": \"example:spool\", \"field\": \"tension\", \"domain\": {\"int\": [0, 1000000]}}},"
                + "\"cases\": ["
                + "{\"when\": {\"lit\": true, \"thread\": [\"loose\", \"taut\"]}, \"model\": \"example:item/spool_lit_{thread}\"},"
                + "{\"when\": {\"thread\": \"taut\"}, \"model\": \"example:item/spool_taut\"},"
                + "{\"when\": {\"thread\": \"loose\"}, \"model\": \"example:item/spool_loose\"}]}");
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
