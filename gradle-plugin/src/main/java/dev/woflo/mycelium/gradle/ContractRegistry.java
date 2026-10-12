package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code contracts/registry.json} from a Mycelium checkout: the one place a contract's id, path and package are
 * declared together.
 *
 * <p>Strict by design. Every field the file may carry is read here, and an unknown field is an error, so the file can
 * never again declare data nothing checks (it once carried four such fields, one described as verified when nothing
 * verified it). {@code provenance} is the one annotation: history for a reader, never a claim about the present.
 */
public final class ContractRegistry {
    /** The fields a row may carry. Anything else is refused. */
    static final Set<String> FIELDS = Set.of("id", "path", "package", "status", "supersedes", "requires", "provenance");
    static final Set<String> TOP = Set.of("schema", "note", "contracts");
    static final int SCHEMA = 2;

    /**
     * One contract package.
     *
     * @param status     what exists for it (the file's note defines each value); copied into the evidence manifest
     * @param supersedes the id this one replaces, or null; a build may import one of the two, never both
     * @param requires   the contracts whose models this one's model may import (exact ids; docs/decisions/0014): they
     *                   exist, are not retired and are acyclic, which {@link #parse} refuses otherwise
     * @param provenance who it was written for and where it came from: history, not checked
     */
    public record Entry(String id, String path, String pkg, String status, String supersedes, List<String> requires,
                        String provenance) {
        public Entry {
            requires = List.copyOf(requires);
        }

        public Entry(String id, String path, String pkg, String status, String supersedes, String provenance) {
            this(id, path, pkg, status, supersedes, List.of(), provenance);
        }

        public String slug() {
            return path;
        }

        /** {@code mycelium.world_copy.v1} → {@code world_copy.v1}. */
        public String relativePackage() {
            String prefix = "mycelium.";
            if (!pkg.startsWith(prefix)) {
                throw new IllegalStateException("contract package must start with 'mycelium.': " + pkg);
            }
            return pkg.substring(prefix.length());
        }

        /** The harness main: {@code <package>.harness.Run}, at {@code contracts/<path>/src/<package as a path>/harness/Run.java}. */
        public String runMain() {
            return pkg + ".harness.Run";
        }

        /** Where {@link #runMain()} must be, relative to the Mycelium root: the one rule every runner uses. */
        public String runFile() {
            return "contracts/" + path + "/src/" + pkg.replace('.', '/') + "/harness/Run.java";
        }
    }

    private final Map<String, Entry> byId = new LinkedHashMap<>();

    public static ContractRegistry load(File myceliumRoot) {
        File file = new File(myceliumRoot, "contracts/registry.json");
        if (!file.isFile()) {
            throw new IllegalStateException("Mycelium registry not found: " + file);
        }
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + file, e);
        }
    }

    /** Parses registry text; {@code where} names it in errors. */
    static ContractRegistry parse(String text, String where) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        for (String key : root.keySet()) {
            if (!TOP.contains(key)) {
                throw new IllegalStateException(where + ": unknown top-level field '" + key + "' (allowed: " + TOP + ")");
            }
        }
        if (!root.has("schema") || root.get("schema").getAsInt() != SCHEMA) {
            throw new IllegalStateException(where + ": schema must be " + SCHEMA);
        }
        ContractRegistry registry = new ContractRegistry();
        JsonArray contracts = root.getAsJsonArray("contracts");
        for (JsonElement element : contracts) {
            JsonObject o = element.getAsJsonObject();
            String id = o.has("id") ? o.get("id").getAsString() : "?";
            for (String key : o.keySet()) {
                if (!FIELDS.contains(key)) {
                    throw new IllegalStateException(where + ": contract " + id + " has unknown field '" + key
                            + "' (allowed: " + FIELDS + "); a field nothing reads is a claim nothing checks");
                }
            }
            for (String required : List.of("id", "path", "package", "status")) {
                if (!o.has(required) || o.get(required).getAsString().isBlank()) {
                    throw new IllegalStateException(where + ": contract " + id + " has no '" + required + "'");
                }
            }
            Entry entry = new Entry(id, o.get("path").getAsString(), o.get("package").getAsString(),
                    o.get("status").getAsString(), text(o, "supersedes"), requires(o, id, where), text(o, "provenance"));
            if (registry.byId.put(entry.id(), entry) != null) {
                throw new IllegalStateException(where + ": contract " + id + " is listed twice");
            }
        }
        for (Entry e : registry.byId.values()) {
            if (e.supersedes() != null && !registry.byId.containsKey(e.supersedes())) {
                throw new IllegalStateException(where + ": " + e.id() + " supersedes unknown contract " + e.supersedes());
            }
        }
        registry.checkRequirements(where);
        return registry;
    }

    private static List<String> requires(JsonObject o, String id, String where) {
        List<String> out = new ArrayList<>();
        if (o.has("requires")) {
            if (!o.get("requires").isJsonArray()) {
                throw new IllegalStateException(where + ": contract " + id + ": 'requires' is an array of contract ids");
            }
            for (JsonElement e : o.getAsJsonArray("requires")) {
                String r = e.isJsonPrimitive() ? e.getAsString() : "";
                if (r.isBlank() || out.contains(r)) {
                    throw new IllegalStateException(where + ": contract " + id + ": 'requires' lists exact contract ids, once each");
                }
                out.add(r);
            }
        }
        return out;
    }

    /** Every required id exists, is not retired and is not the contract itself, and no requirement leads back to its origin. */
    private void checkRequirements(String where) {
        for (Entry e : byId.values()) {
            for (String r : e.requires()) {
                Entry target = byId.get(r);
                if (target == null) {
                    throw new IllegalStateException(where + ": " + e.id() + " requires unknown contract " + r);
                }
                if (target.status().equals("retired")) {
                    throw new IllegalStateException(where + ": " + e.id() + " requires " + r + ", which is retired");
                }
                if (r.equals(e.id())) {
                    throw new IllegalStateException(where + ": " + e.id() + " requires itself");
                }
            }
        }
        for (Entry e : byId.values()) {
            List<String> path = new ArrayList<>();
            path.add(e.id());
            cycle(e, e.id(), path, where);
        }
    }

    private void cycle(Entry at, String origin, List<String> path, String where) {
        for (String r : at.requires()) {
            if (r.equals(origin)) {
                throw new IllegalStateException(where + ": requirements form a cycle: " + String.join(" -> ", path) + " -> " + r);
            }
            if (!path.contains(r)) {
                path.add(r);
                cycle(byId.get(r), origin, path, where);
                path.remove(path.size() - 1);
            }
        }
    }

    /**
     * What {@code entry} needs imported with it, transitively and dependencies first (a requirement comes before what
     * requires it); {@code entry} itself is not in the list.
     */
    public List<Entry> closure(Entry entry) {
        List<Entry> out = new ArrayList<>();
        collect(entry, out);
        return out;
    }

    private void collect(Entry entry, List<Entry> out) {
        for (String r : entry.requires()) {
            Entry target = require(r);
            if (!out.contains(target)) {
                collect(target, out);
                out.add(target);
            }
        }
    }

    /**
     * A selection plus everything it requires, each once, requirements first. A contract and the one it supersedes are
     * refused here too, whichever of them came in as a requirement.
     */
    public List<Entry> withRequirements(List<Entry> selected) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : selected) {
            for (Entry r : closure(e)) {
                if (!out.contains(r)) {
                    out.add(r);
                }
            }
            if (!out.contains(e)) {
                out.add(e);
            }
        }
        for (Entry e : out) {
            if (e.supersedes() != null && out.stream().anyMatch(o -> o.id().equals(e.supersedes()))) {
                throw new IllegalStateException("Mycelium: " + e.id() + " supersedes " + e.supersedes()
                        + ", and both would be imported (one as a requirement of another selected contract)");
            }
        }
        return out;
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).getAsString().isBlank() ? o.get(key).getAsString() : null;
    }

    public Entry require(String id) {
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new IllegalStateException("unknown Mycelium contract '" + id + "'. Known: " + byId.keySet());
        }
        return entry;
    }

    public List<Entry> all() {
        return new ArrayList<>(byId.values());
    }

    /**
     * The contracts a build selected; empty selects all (a verification project). A selection holding a contract and the
     * one it supersedes is refused: both would import, and their types would meet at the consumer's join under the same
     * names (two {@code TravelReceipt}s, two {@code Feedback.traveled}), which compiles and is wrong.
     */
    public List<Entry> resolve(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return all();
        }
        List<Entry> out = new ArrayList<>();
        for (String id : ids) {
            out.add(require(id));
        }
        for (Entry e : out) {
            if (e.supersedes() != null && ids.contains(e.supersedes())) {
                throw new IllegalStateException("Mycelium: " + e.id() + " supersedes " + e.supersedes()
                        + "; a build imports one of them, never both. Keep " + e.id() + " (or keep " + e.supersedes()
                        + " until you move).");
            }
        }
        return out;
    }
}
