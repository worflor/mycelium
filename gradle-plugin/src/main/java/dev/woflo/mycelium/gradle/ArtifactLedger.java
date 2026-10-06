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
import java.util.TreeMap;

/**
 * What an artifact claims, in one file its consumer keeps ({@code artifacts.json}, docs/decisions/0009): the loader, the
 * Minecraft versions it says it runs on (a range), the versions that prove it (keystones, where servers really run, and
 * flex, one on each side of a wall) and the loader and API versions each proof pins. Everything else the consumer
 * derives from it, the loader's declared compatibility, the version table it installs from and the servers it is tested
 * on, is read from here rather than written again; a row may carry any other field of its own, which this reads past.
 *
 * <pre>
 * { "schema": 1, "artifacts": [
 *   { "id": "fabric-1.21.1", "loader": "fabric", "node": "1.21.1",
 *     "range": {"from": "1.21", "to": "1.21.1"},
 *     "keystones": ["1.21.1"], "flex": [], "pending": [],
 *     "pins": {"1.21.1": {"fabric_api": "0.116.17+1.21.1"}} } ] }
 * </pre>
 * A proof is a version, or {@code {"mc": version, "on": [platform, ...]}} for an artifact that runs on several platforms
 * of one family (a plugin on Spigot, Paper, Purpur and Folia): each platform must exist on that version. {@code platforms}
 * names every platform the artifact claims, {@code loader} the one its metadata and walls are judged as; {@code jar} is
 * the file's own name for it where that differs. {@code pending} lists proofs declared but not yet run. {@code pins} map a
 * proof version (or {@code "*"}, every one) to named versions the gate does not read.
 */
public final class ArtifactLedger {
    /** A version the artifact is proven on, and the platforms it runs there (empty: the artifact's loader). */
    public record Proof(String mc, List<String> on) {
    }

    /** One artifact's claim. {@code from} and {@code to} are inclusive; {@code to} may be a line ({@code 26.3.x}). */
    public record Artifact(String id, String loader, String node, List<String> platforms, String from, String to,
                           List<Proof> keystones, List<Proof> flex, List<String> pending,
                           Map<String, Map<String, String>> pins) {
        /** Every proof, keystones first. */
        public List<Proof> proofs() {
            List<Proof> all = new ArrayList<>(keystones);
            all.addAll(flex);
            return all;
        }

        /** The pins of {@code version}: those of {@code "*"}, then its own. */
        public Map<String, String> pinsFor(String version) {
            Map<String, String> out = new LinkedHashMap<>();
            out.putAll(pins.getOrDefault("*", Map.of()));
            out.putAll(pins.getOrDefault(version, Map.of()));
            return out;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("loader", loader);
            o.addProperty("node", node);
            o.add("platforms", strings(platforms));
            JsonObject range = new JsonObject();
            range.addProperty("from", from);
            range.addProperty("to", to);
            o.add("range", range);
            o.add("keystones", proofs(keystones));
            o.add("flex", proofs(flex));
            o.add("pending", strings(pending));
            JsonObject p = new JsonObject();
            new TreeMap<>(pins).forEach((v, m) -> {
                JsonObject one = new JsonObject();
                new TreeMap<>(m).forEach(one::addProperty);
                p.add(v, one);
            });
            o.add("pins", p);
            return o;
        }

        private static JsonArray strings(List<String> list) {
            JsonArray a = new JsonArray();
            list.forEach(a::add);
            return a;
        }

        private static JsonArray proofs(List<Proof> list) {
            JsonArray a = new JsonArray();
            for (Proof p : list) {
                JsonObject o = new JsonObject();
                o.addProperty("mc", p.mc());
                o.add("on", strings(p.on()));
                a.add(o);
            }
            return a;
        }

        /** The artifact {@link #toJson} wrote. */
        public static Artifact fromJson(JsonObject o, String where) {
            return ArtifactLedger.artifact(o, where);
        }
    }

    private final List<Artifact> artifacts;

    private ArtifactLedger(List<Artifact> artifacts) {
        this.artifacts = artifacts;
    }

    public static ArtifactLedger load(File file) {
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("the artifact ledger is not there: " + file, e);
        }
    }

    /** The ledger in {@code text}; {@code where} names it in a refusal. A malformed row is refused, by name. */
    static ArtifactLedger parse(String text, String where) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        if (root == null || !root.has("schema") || root.get("schema").getAsInt() != 1) {
            throw new IllegalStateException(where + ": schema must be 1");
        }
        if (!root.has("artifacts") || !root.get("artifacts").isJsonArray()) {
            throw new IllegalStateException(where + ": no 'artifacts' array");
        }
        List<Artifact> out = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonElement e : root.getAsJsonArray("artifacts")) {
            Artifact a = artifact(e.getAsJsonObject(), where);
            if (!ids.add(a.id())) {
                throw new IllegalStateException(where + ": two artifacts are called '" + a.id() + "'");
            }
            out.add(a);
        }
        return new ArtifactLedger(out);
    }

    static Artifact artifact(JsonObject o, String where) {
        String id = text(o, "id", where, "an artifact");
        String at = where + ": '" + id + "'";
        String loader = text(o, "loader", at, "an artifact");
        String node = text(o, "node", at, "an artifact");
        if (!o.has("range") || !o.get("range").isJsonObject()) {
            throw new IllegalStateException(at + " has no range {\"from\": version, \"to\": version}");
        }
        JsonObject range = o.getAsJsonObject("range");
        String from = text(range, "from", at, "its range");
        String to = text(range, "to", at, "its range");
        List<String> platforms = new ArrayList<>();
        if (o.has("platforms")) {
            o.getAsJsonArray("platforms").forEach(p -> platforms.add(p.getAsString()));
        }
        List<Proof> keystones = proofs(o, "keystones", at);
        List<Proof> flex = proofs(o, "flex", at);
        List<String> pending = new ArrayList<>();
        if (o.has("pending")) {
            o.getAsJsonArray("pending").forEach(p -> pending.add(p.getAsString()));
        }
        Map<String, Map<String, String>> pins = new LinkedHashMap<>();
        if (o.has("pins")) {
            for (Map.Entry<String, JsonElement> v : o.getAsJsonObject("pins").entrySet()) {
                Map<String, String> one = new LinkedHashMap<>();
                v.getValue().getAsJsonObject().entrySet().forEach(p -> one.put(p.getKey(), p.getValue().getAsString()));
                pins.put(v.getKey(), one);
            }
        }
        return new Artifact(id, loader, node, platforms, from, to, keystones, flex, pending, pins);
    }

    private static List<Proof> proofs(JsonObject o, String key, String at) {
        List<Proof> out = new ArrayList<>();
        if (!o.has(key)) {
            return out;
        }
        if (!o.get(key).isJsonArray()) {
            throw new IllegalStateException(at + ": '" + key + "' is a list of versions");
        }
        for (JsonElement p : o.getAsJsonArray(key)) {
            if (p.isJsonPrimitive()) {
                out.add(new Proof(p.getAsString(), List.of()));
            } else {
                JsonObject po = p.getAsJsonObject();
                List<String> on = new ArrayList<>();
                if (po.has("on")) {
                    po.getAsJsonArray("on").forEach(x -> on.add(x.getAsString()));
                }
                out.add(new Proof(text(po, "mc", at, "a proof in '" + key + "'"), on));
            }
        }
        return out;
    }

    private static String text(JsonObject o, String key, String where, String of) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || o.get(key).getAsString().isBlank()) {
            throw new IllegalStateException(where + ": " + of + " has no '" + key + "'");
        }
        return o.get(key).getAsString();
    }

    public List<Artifact> all() {
        return List.copyOf(artifacts);
    }

    /** The artifact called {@code id}; a miss names the closest ids and what the ledger holds for that loader. */
    public Artifact require(String id) {
        for (Artifact a : artifacts) {
            if (a.id().equals(id)) {
                return a;
            }
        }
        List<String> near = new ArrayList<>();
        for (Artifact a : artifacts) {
            if (a.id().startsWith(id.contains("-") ? id.substring(0, id.indexOf('-') + 1) : id) || a.id().contains(id)) {
                near.add(a.id());
            }
        }
        throw new IllegalArgumentException("the artifact ledger has no artifact '" + id + "'. "
                + (near.isEmpty() ? "It holds " + ids() : "Closest: " + near) + ". Add its row, or name an existing one.");
    }

    private List<String> ids() {
        List<String> ids = new ArrayList<>();
        artifacts.forEach(a -> ids.add(a.id()));
        return ids;
    }
}
