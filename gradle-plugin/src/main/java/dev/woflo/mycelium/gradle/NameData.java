package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
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
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The name data the linkage gate reads ({@code eras/names.json}, docs/decisions/0010): for each naming of the game
 * ({@link Eras#NAMES}) and each Minecraft version it exists for, the published files that say which classes, methods and
 * fields the game has under that naming, each pinned by SHA-256. A version with no entry has no name data, and the gate
 * does not claim to have linked anything there.
 *
 * <p>An entry says how its inputs are {@code reads read} ({@link #READS}) and which class names it {@code owns} (the
 * internal-name prefixes it speaks for: a reference to any other class is not the game's, and is not checked). A
 * namespace may state either once for all its versions, and a version overrides it.
 */
public final class NameData {
    /**
     * How an entry's inputs are read: {@code srg-flat} (Searge's {@code joined.srg}: classes, numbered methods and fields),
     * {@code tiny-flat} (a Tiny v2 file such as Fabric's intermediary: the same), {@code jar} (the game's, or an
     * API's, own class files in the names the artifact runs on: every member, with its inheritance) and
     * {@code proguard-jar} (an obfuscated game jar and Mojang's ProGuard-format mappings, read as a {@code jar} in the official names).
     */
    public static final Map<String, Set<String>> READS = Map.of(
            "srg-flat", Set.of("joined"),
            "tiny-flat", Set.of("mappings"),
            "jar", Set.of("game"),
            "proguard-jar", Set.of("game", "mappings"));

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /** One pinned file: its role in the entry, where it is fetched from, its SHA-256 and, in an archive, which entry holds it. */
    public record Input(String role, String url, String sha256, String entry) {
    }

    /** What the game is, under {@code namespace}, on {@code version}. */
    public record Entry(String namespace, String version, String reads, List<String> owns, List<Input> inputs) {
        /** The input in {@code role}; the entry's reader names which roles it needs, so a miss is a malformed row. */
        public Input input(String role) {
            for (Input i : inputs) {
                if (i.role().equals(role)) {
                    return i;
                }
            }
            throw new IllegalStateException("the name data for " + namespace + " " + version + " has no '" + role + "' input");
        }
    }

    private final Map<String, Map<String, Entry>> namespaces;
    private final Map<String, JsonObject> resolvers;

    private NameData(Map<String, Map<String, Entry>> namespaces, Map<String, JsonObject> resolvers) {
        this.namespaces = namespaces;
        this.resolvers = resolvers;
    }

    public static NameData load(File file) {
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium name data not found: " + file, e);
        }
    }

    /** The default lock of a Mycelium checkout. */
    public static File lockOf(File myceliumRoot) {
        return new File(myceliumRoot, "eras/names.json");
    }

    /** The lock in {@code text}; {@code where} names it in a refusal. A malformed entry is refused, by name. */
    public static NameData parse(String text, String where) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        if (root == null || !root.has("schema") || root.get("schema").getAsInt() != 1) {
            throw new IllegalStateException(where + ": schema must be 1");
        }
        if (!root.has("namespaces") || !root.get("namespaces").isJsonObject()) {
            throw new IllegalStateException(where + ": no 'namespaces' object");
        }
        Map<String, Map<String, Entry>> out = new LinkedHashMap<>();
        Map<String, JsonObject> resolvers = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> n : root.getAsJsonObject("namespaces").entrySet()) {
            String ns = n.getKey();
            String at = where + ": namespace '" + ns + "'";
            if (!Eras.NAMES.contains(ns)) {
                throw new IllegalStateException(at + " is not a name the era table knows (" + new java.util.TreeSet<>(Eras.NAMES) + ")");
            }
            JsonObject o = n.getValue().getAsJsonObject();
            String reads = o.has("reads") ? o.get("reads").getAsString() : null;
            List<String> owns = o.has("owns") ? strings(o.getAsJsonArray("owns")) : null;
            if (!o.has("versions") || !o.get("versions").isJsonObject()) {
                throw new IllegalStateException(at + " has no 'versions' object");
            }
            if (o.has("resolve")) {
                resolvers.put(ns, o.getAsJsonObject("resolve"));
            }
            Map<String, Entry> versions = new TreeMap<>(Eras::compare);
            for (Map.Entry<String, JsonElement> v : o.getAsJsonObject("versions").entrySet()) {
                String version = v.getKey();
                String here = at + " " + version;
                if (!Eras.readable(version) || Eras.isLine(version)) {
                    throw new IllegalStateException(here + ": '" + version + "' is not a Minecraft version");
                }
                JsonObject e = v.getValue().getAsJsonObject();
                String r = e.has("reads") ? e.get("reads").getAsString() : reads;
                List<String> w = e.has("owns") ? strings(e.getAsJsonArray("owns")) : owns;
                if (r == null || !READS.containsKey(r)) {
                    throw new IllegalStateException(here + ": reads '" + r + "' is not one of " + new java.util.TreeSet<>(READS.keySet()));
                }
                if (w == null || w.isEmpty() || w.stream().anyMatch(p -> !p.endsWith("/"))) {
                    throw new IllegalStateException(here + ": owns is a list of internal-name prefixes, each ending in '/' (net/minecraft/)");
                }
                List<Input> inputs = new ArrayList<>();
                if (!e.has("inputs") || !e.get("inputs").isJsonArray()) {
                    throw new IllegalStateException(here + ": no 'inputs' list");
                }
                for (JsonElement ie : e.getAsJsonArray("inputs")) {
                    JsonObject io = ie.getAsJsonObject();
                    String role = text(io, "role", here);
                    String url = text(io, "url", here);
                    String sha = text(io, "sha256", here);
                    if (!READS.get(r).contains(role)) {
                        throw new IllegalStateException(here + ": reads '" + r + "' takes the inputs " + new java.util.TreeSet<>(READS.get(r))
                                + ", not '" + role + "'");
                    }
                    if (!url.startsWith("https://") && !url.startsWith("file:")) {
                        throw new IllegalStateException(here + ": the " + role + " input's url must be https (or file: for a fixture): " + url);
                    }
                    if (!SHA256.matcher(sha).matches()) {
                        throw new IllegalStateException(here + ": the " + role + " input's sha256 must be 64 lowercase hex digits");
                    }
                    inputs.add(new Input(role, url, sha, io.has("entry") ? io.get("entry").getAsString() : null));
                }
                for (String role : READS.get(r)) {
                    if (inputs.stream().noneMatch(i -> i.role().equals(role))) {
                        throw new IllegalStateException(here + ": reads '" + r + "' needs a '" + role + "' input");
                    }
                }
                versions.put(version, new Entry(ns, version, r, w, inputs));
            }
            out.put(ns, versions);
        }
        return new NameData(out, resolvers);
    }

    /** The naming spaces this lock holds data for. */
    public Set<String> namespaces() {
        return namespaces.keySet();
    }

    /** Every version {@code namespace} has data for, oldest first. */
    public List<String> versions(String namespace) {
        Map<String, Entry> v = namespaces.get(namespace);
        return v == null ? List.of() : new ArrayList<>(v.keySet());
    }

    /** The entry for {@code version} under {@code namespace}, or {@code null}: no name data, so nothing was linked there. */
    public Entry entry(String namespace, String version) {
        Map<String, Entry> v = namespaces.get(namespace);
        return v == null ? null : v.get(version);
    }

    /** How {@code myceliumRefreshNames} finds a version's inputs for {@code namespace}, as the lock states it, or {@code null}. */
    public JsonObject resolver(String namespace) {
        return resolvers.get(namespace);
    }

    private static List<String> strings(com.google.gson.JsonArray a) {
        List<String> out = new ArrayList<>();
        a.forEach(x -> out.add(x.getAsString()));
        return out;
    }

    private static String text(JsonObject o, String key, String where) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive() || o.get(key).getAsString().isBlank()) {
            throw new IllegalStateException(where + ": an input has no '" + key + "'");
        }
        return o.get(key).getAsString();
    }
}
