import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prints, one per line and relative to the Mycelium root, the model directories of every contract that the package
 * {@code contracts/<slug>} requires (transitively, requirements first), as {@code contracts/registry.json} declares them.
 * The runner scripts compile these with the package (docs/decisions/0014); the registry is read here and nowhere else in
 * the shell. Run from the root: {@code java harness/tools/RequiredModels.java <slug>}.
 */
public final class RequiredModels {
    private RequiredModels() {
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String registry = new String(Files.readAllBytes(Paths.get("contracts/registry.json")), StandardCharsets.UTF_8);
        Map<String, Object> root = (Map<String, Object>) new Reader(registry).value();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Map<String, Map<String, Object>> bySlug = new LinkedHashMap<>();
        for (Object o : (List<Object>) root.get("contracts")) {
            Map<String, Object> row = (Map<String, Object>) o;
            byId.put((String) row.get("id"), row);
            bySlug.put((String) row.get("path"), row);
        }
        Map<String, Object> start = bySlug.get(args[0]);
        if (start == null) {
            return;
        }
        List<String> order = new ArrayList<>();
        collect(start, byId, order);
        for (String id : order) {
            Map<String, Object> row = byId.get(id);
            System.out.println("contracts/" + row.get("path") + "/src/" + ((String) row.get("package")).replace('.', '/') + "/model");
        }
    }

    private static void collect(Map<String, Object> row, Map<String, Map<String, Object>> byId, List<String> order) {
        Object requires = row.get("requires");
        if (requires == null) {
            return;
        }
        for (Object id : (List<Object>) requires) {
            Map<String, Object> target = byId.get((String) id);
            if (target == null) {
                throw new IllegalStateException("requires unknown contract " + id);
            }
            if (!order.contains(id)) {
                collect(target, byId, order);
                order.add((String) id);
            }
        }
    }

    /** Just enough JSON for the registry: objects, arrays and strings. */
    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) {
            this.s = s;
        }

        Object value() {
            skip();
            char c = s.charAt(i);
            if (c == '{') {
                Map<String, Object> m = new LinkedHashMap<>();
                i++;
                skip();
                if (s.charAt(i) == '}') {
                    i++;
                    return m;
                }
                while (true) {
                    skip();
                    String k = string();
                    skip();
                    i++; // ':'
                    m.put(k, value());
                    skip();
                    if (s.charAt(i++) == '}') {
                        return m;
                    }
                }
            }
            if (c == '[') {
                List<Object> l = new ArrayList<>();
                i++;
                skip();
                if (s.charAt(i) == ']') {
                    i++;
                    return l;
                }
                while (true) {
                    l.add(value());
                    skip();
                    if (s.charAt(i++) == ']') {
                        return l;
                    }
                }
            }
            if (c == '"') {
                return string();
            }
            int start = i;
            while (i < s.length() && ",}] \r\n\t".indexOf(s.charAt(i)) < 0) {
                i++;
            }
            return s.substring(start, i);
        }

        String string() {
            StringBuilder b = new StringBuilder();
            i++;
            while (s.charAt(i) != '"') {
                char c = s.charAt(i++);
                if (c == '\\') {
                    char e = s.charAt(i++);
                    b.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                } else {
                    b.append(c);
                }
            }
            i++;
            return b.toString();
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
