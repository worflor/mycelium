package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Writes JSON object keys in the order a hash map happens to hold them, and so in an order that is not the canonical one (S6). */
public final class HashOrderEmitter extends ReferenceSigil {
    @Override
    protected String render(Object tree) {
        StringBuilder b = new StringBuilder();
        write(b, tree, 0);
        return b.append('\n').toString();
    }

    private static void write(StringBuilder b, Object v, int depth) {
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                b.append("{}");
                return;
            }
            b.append("{\n");
            int n = 0;
            for (Map.Entry<?, ?> e : new HashMap<Object, Object>(m).entrySet()) {
                indent(b, depth + 1);
                Json.quote(b, (String) e.getKey());
                b.append(": ");
                write(b, e.getValue(), depth + 1);
                b.append(++n < m.size() ? ",\n" : "\n");
            }
            indent(b, depth);
            b.append('}');
        } else if (v instanceof List<?> list) {
            if (list.isEmpty()) {
                b.append("[]");
                return;
            }
            b.append("[\n");
            for (int k = 0; k < list.size(); k++) {
                indent(b, depth + 1);
                write(b, list.get(k), depth + 1);
                b.append(k + 1 < list.size() ? ",\n" : "\n");
            }
            indent(b, depth);
            b.append(']');
        } else if (v instanceof String s) {
            Json.quote(b, s);
        } else {
            b.append(v);
        }
    }

    private static void indent(StringBuilder b, int depth) {
        for (int k = 0; k < depth; k++) {
            b.append("  ");
        }
    }
}
