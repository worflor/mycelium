package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Bridges (docs/decisions/0016): the native half of a divide, as source, one variant per realization. This class reads a
 * bridge's {@code bridge.json} strictly, chooses the variant and the overlays a Minecraft version draws, and lays the chosen
 * sources out under a mod's namespace, the way a contract's model is imported. It touches no Gradle API, so the repository
 * check and the self-tests use it as the plugin does.
 *
 * <pre>
 * bridges/&lt;slug&gt;/
 *   bridge.json                          the bridge: its contract and port, its divide, its variants and their overlays
 *   &lt;variant&gt;/src/mycelium/...            the variant's source, in the bridge's package
 *   &lt;variant&gt;/conformance/mycelium/...   what the conformance cases need of this variant (the cases' {@code Wire})
 *   &lt;variant&gt;/overlays/&lt;name&gt;/{src,conformance}/mycelium/...   files that replace the variant's, for a range of versions
 *   conformance/mycelium/...             the conformance cases, written against the port and not a variant
 * </pre>
 */
public final class Bridges {
    private Bridges() {
    }

    /** The fields a {@code bridge.json} may carry; anything else is refused. */
    static final Set<String> FIELDS = Set.of("schema", "id", "contract", "port", "implementation", "package", "divide",
            "writtenIn", "what", "variants");
    static final Set<String> VARIANT_FIELDS = Set.of("overlays");
    static final Set<String> OVERLAY_FIELDS = Set.of("name", "from", "to", "why");
    static final int SCHEMA = 1;

    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");
    private static final Pattern PACKAGE = Pattern.compile("mycelium\\.bridges\\.[a-z][a-z0-9_]*");
    private static final Pattern JAVA_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");
    private static final Pattern REFERENCE = Pattern.compile("\\bmycelium\\.(bridges\\.[a-z][a-z0-9_]*|[a-z][a-z0-9_]*\\.v\\d+)\\b");

    /** A range of versions inside a variant's whose files replace the variant's. */
    public record Overlay(String name, String from, String to, String why) {
        public boolean covers(String minecraft) {
            return Eras.atLeast(minecraft, from) && Eras.atMost(minecraft, to);
        }
    }

    public record Variant(String name, List<Overlay> overlays) {
    }

    /**
     * One bridge.
     *
     * @param slug           the directory name under {@code bridges/}, and what a mod calls it
     * @param id             the bridge's id (the divide it crosses, by default)
     * @param contract       the contract id whose port it implements
     * @param port           the port: an interface in the contract's model package
     * @param implementation the class a variant defines that implements the port
     * @param pkg            the package of the bridge's source ({@code mycelium.bridges.<slug_>}), rewritten under the mod's namespace
     * @param divide         the name of its realization table in {@code eras/minecraft.json}
     * @param writtenIn      the namespaces of {@code eras/names.json} whose names the variants are written in
     */
    public record Bridge(String slug, String id, String contract, String port, String implementation, String pkg, String divide,
                         List<String> writtenIn, String what, Map<String, Variant> variants, Path dir) {
        /** {@code mycelium.bridges.item_carrier} as {@code bridges.item_carrier}: the part a mod's namespace goes before. */
        public String relativePackage() {
            return pkg.substring("mycelium.".length());
        }

        public Path variantDir(String variant) {
            return dir.resolve(variant);
        }
    }

    // ---------------------------------------------------------------------------------------------------- reading

    /** Every bridge under {@code <root>/bridges/}, strictly read. A directory with no {@code bridge.json} is not one. */
    public static List<Bridge> load(Path root) throws IOException {
        Path dir = root.resolve("bridges");
        List<Bridge> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> dirs;
        try (Stream<Path> list = Files.list(dir)) {
            dirs = list.filter(Files::isDirectory).sorted().toList();
        }
        for (Path d : dirs) {
            Path file = d.resolve("bridge.json");
            if (Files.isRegularFile(file)) {
                out.add(parse(Files.readString(file, StandardCharsets.UTF_8), d.getFileName().toString(), d, file.toString()));
            }
        }
        return out;
    }

    public static Bridge require(Path root, String slug) throws IOException {
        for (Bridge b : load(root)) {
            if (b.slug().equals(slug)) {
                return b;
            }
        }
        throw new IllegalStateException("Mycelium: no bridge '" + slug + "' under bridges/");
    }

    static Bridge parse(String text, String slug, Path dir, String where) {
        JsonObject o;
        try {
            o = new Gson().fromJson(text, JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException(where + ": not JSON: " + e.getMessage());
        }
        if (o == null) {
            throw new IllegalStateException(where + ": empty");
        }
        for (String key : o.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new IllegalStateException(where + ": unknown field '" + key + "' (allowed: " + new java.util.TreeSet<>(FIELDS)
                        + "); a field nothing reads is a claim nothing checks");
            }
        }
        if (!SLUG.matcher(slug).matches()) {
            throw new IllegalStateException(where + ": the directory '" + slug + "' is not a kebab-case slug");
        }
        if (!o.has("schema") || o.get("schema").getAsInt() != SCHEMA) {
            throw new IllegalStateException(where + ": schema must be " + SCHEMA);
        }
        for (String required : List.of("id", "contract", "port", "implementation", "package", "divide", "writtenIn", "variants")) {
            if (!o.has(required)) {
                throw new IllegalStateException(where + ": no '" + required + "'");
            }
        }
        String pkg = text(o, "package", where);
        if (!PACKAGE.matcher(pkg).matches() || !pkg.equals("mycelium.bridges." + slug.replace('-', '_'))) {
            throw new IllegalStateException(where + ": the package must be mycelium.bridges." + slug.replace('-', '_') + ", not '" + pkg + "'");
        }
        String port = text(o, "port", where);
        String implementation = text(o, "implementation", where);
        if (!JAVA_NAME.matcher(port).matches() || !JAVA_NAME.matcher(implementation).matches()) {
            throw new IllegalStateException(where + ": port and implementation are simple class names");
        }
        List<String> writtenIn = new ArrayList<>();
        if (!o.get("writtenIn").isJsonArray() || o.getAsJsonArray("writtenIn").isEmpty()) {
            throw new IllegalStateException(where + ": 'writtenIn' lists the name data namespaces (eras/names.json) the variants are written in");
        }
        for (JsonElement e : o.getAsJsonArray("writtenIn")) {
            writtenIn.add(e.getAsString());
        }
        if (!o.get("variants").isJsonObject() || o.getAsJsonObject("variants").isEmpty()) {
            throw new IllegalStateException(where + ": 'variants' is an object of at least one variant");
        }
        Map<String, Variant> variants = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> v : o.getAsJsonObject("variants").entrySet()) {
            if (!WORD.matcher(v.getKey()).matches() || v.getKey().equals("none")) {
                throw new IllegalStateException(where + ": a variant is a kebab-case name other than 'none', not '" + v.getKey() + "'");
            }
            JsonObject vo = v.getValue().isJsonObject() ? v.getValue().getAsJsonObject() : new JsonObject();
            for (String key : vo.keySet()) {
                if (!VARIANT_FIELDS.contains(key)) {
                    throw new IllegalStateException(where + ": variant " + v.getKey() + " has unknown field '" + key + "'");
                }
            }
            List<Overlay> overlays = new ArrayList<>();
            if (vo.has("overlays")) {
                JsonArray rows = vo.getAsJsonArray("overlays");
                for (JsonElement e : rows) {
                    JsonObject r = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
                    for (String key : r.keySet()) {
                        if (!OVERLAY_FIELDS.contains(key)) {
                            throw new IllegalStateException(where + ": an overlay of " + v.getKey() + " has unknown field '" + key + "'");
                        }
                    }
                    for (String required : List.of("name", "from", "to")) {
                        if (!r.has(required)) {
                            throw new IllegalStateException(where + ": an overlay of " + v.getKey() + " has no '" + required + "'");
                        }
                    }
                    String name = r.get("name").getAsString();
                    if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                        throw new IllegalStateException(where + ": the overlay name '" + name + "' is not a directory name");
                    }
                    overlays.add(new Overlay(name, r.get("from").getAsString(), r.get("to").getAsString(),
                            r.has("why") ? r.get("why").getAsString() : ""));
                }
            }
            variants.put(v.getKey(), new Variant(v.getKey(), List.copyOf(overlays)));
        }
        return new Bridge(slug, text(o, "id", where), text(o, "contract", where), port, implementation, pkg, text(o, "divide", where),
                List.copyOf(writtenIn), o.has("what") ? o.get("what").getAsString() : "", Map.copyOf(variants), dir);
    }

    private static String text(JsonObject o, String key, String where) {
        if (!o.get(key).isJsonPrimitive() || o.get(key).getAsString().isBlank()) {
            throw new IllegalStateException(where + ": '" + key + "' is a non-empty string");
        }
        return o.get(key).getAsString();
    }

    // ---------------------------------------------------------------------------------------------------- selecting

    /**
     * What a Minecraft version draws from a bridge: the variant its realization row names and the overlays that cover the
     * version, or {@code none} (the game cannot do it there, and {@code why} says so). The plugin holds no version number:
     * the row is in {@code eras/minecraft.json}.
     */
    public record Selection(Bridge bridge, String minecraft, String realization, Variant variant, List<Overlay> overlays, String why) {
        /** Whether the bridge maps on this version. */
        public boolean maps() {
            return variant != null;
        }

        /** {@code custom-data} or {@code custom-data + 1.21.5}: for a report. */
        public String describe() {
            if (variant == null) {
                return "none";
            }
            return variant.name() + overlays.stream().map(o -> " + " + o.name()).reduce("", String::concat);
        }
    }

    public static Selection select(Bridge bridge, Eras eras, String minecraft) {
        Eras.Realization row = eras.realization(bridge.divide(), minecraft);
        if (row == null) {
            throw new IllegalStateException("Mycelium: no " + bridge.divide() + " row covers Minecraft '" + minecraft
                    + "' (eras/minecraft.json, \"realizations\"), so bridge " + bridge.slug() + " cannot say what it is there");
        }
        if (row.realization().equals("none")) {
            return new Selection(bridge, minecraft, "none", null, List.of(), row.what());
        }
        Variant variant = bridge.variants().get(row.realization());
        if (variant == null) {
            throw new IllegalStateException("Mycelium: the " + bridge.divide() + " table gives Minecraft " + minecraft + " the realization '"
                    + row.realization() + "', which bridge " + bridge.slug() + " has no variant for (it has " + bridge.variants().keySet() + ")");
        }
        List<Overlay> overlays = new ArrayList<>();
        for (Overlay o : variant.overlays()) {
            if (o.covers(minecraft)) {
                overlays.add(o);
            }
        }
        return new Selection(bridge, minecraft, row.realization(), variant, List.copyOf(overlays), row.what());
    }

    // ---------------------------------------------------------------------------------------------------- the files

    /** The variant's source files, an overlay's replacing the same-named file (and adding the ones the variant lacks). */
    public static Map<String, Path> sourceFiles(Selection s) throws IOException {
        return files(s, "src");
    }

    /** The conformance cases (the bridge's), with the variant's (and its overlays') files beside them. */
    public static Map<String, Path> conformanceFiles(Selection s) throws IOException {
        if (!s.maps()) {
            return new TreeMap<>();
        }
        Map<String, Path> out = new TreeMap<>(javaFiles(s.bridge().dir().resolve("conformance")));
        out.putAll(files(s, "conformance"));
        return out;
    }

    /**
     * The data files a variant's conformance cases need in the mod's resources (a clock on 26.1 and later is a datapack file, so the
     * case that uses one needs it beside the game's): everything under {@code conformance-resources/} of the variant and of the
     * overlays that cover the version, an overlay's file replacing the same path. They are laid out as they are, no package moving.
     */
    public static Map<String, Path> conformanceResourceFiles(Selection s) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!s.maps()) {
            return out;
        }
        Path variant = s.bridge().variantDir(s.variant().name());
        out.putAll(allFiles(variant.resolve("conformance-resources")));
        for (Overlay o : s.overlays()) {
            out.putAll(allFiles(variant.resolve("overlays").resolve(o.name()).resolve("conformance-resources")));
        }
        return out;
    }

    private static Map<String, Path> files(Selection s, String part) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!s.maps()) {
            return out;
        }
        Path variant = s.bridge().variantDir(s.variant().name());
        out.putAll(javaFiles(variant.resolve(part)));
        for (Overlay o : s.overlays()) {
            out.putAll(javaFiles(variant.resolve("overlays").resolve(o.name()).resolve(part)));
        }
        return out;
    }

    /** Every file under {@code dir}, by its path relative to it (forward slashes). */
    static Map<String, Path> allFiles(Path dir) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), p);
            }
        }
        return out;
    }

    /** The {@code .java} files under {@code dir}, by their path relative to it (forward slashes). */
    static Map<String, Path> javaFiles(Path dir) throws IOException {
        Map<String, Path> out = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".java"))::iterator) {
                out.put(dir.relativize(p).toString().replace('\\', '/'), p);
            }
        }
        return out;
    }

    /** The text of a bridge source under a mod's namespace: {@code mycelium.item_component.v1} and {@code mycelium.bridges.x} move. */
    public static String rewrite(String text, String namespace) {
        Matcher m = REFERENCE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(namespace + "." + m.group(1)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Where a bridge file lands under a mod's namespace: {@code mycelium/bridges/x/Y.java} as {@code <namespace as a path>/bridges/x/Y.java}. */
    static String placed(String relative, String namespace) {
        if (!relative.startsWith("mycelium/")) {
            throw new IllegalStateException("Mycelium: a bridge source is laid out from its package root, mycelium/...; not " + relative);
        }
        return namespace.replace('.', '/') + "/" + relative.substring("mycelium/".length());
    }

    /**
     * Writes the selected sources under {@code srcOut} and, when {@code conformanceOut} is given, the conformance sources
     * under it, all moved under {@code namespace}. A file that reaches the harness is refused, as a model is. Returns the
     * relative paths written (sources, then conformance), for a report.
     */
    public static List<String> materialize(Selection s, String namespace, Path srcOut, Path conformanceOut) throws IOException {
        return materialize(s, namespace, srcOut, conformanceOut, null);
    }

    /** As above, and with {@code resourcesOut} the conformance cases' data files (a datapack clock) land there, as they are. */
    public static List<String> materialize(Selection s, String namespace, Path srcOut, Path conformanceOut, Path resourcesOut) throws IOException {
        List<String> written = new ArrayList<>();
        write(sourceFiles(s), namespace, srcOut, written, "");
        if (conformanceOut != null) {
            write(conformanceFiles(s), namespace, conformanceOut, written, "conformance: ");
            if (resourcesOut != null) {
                for (Map.Entry<String, Path> f : conformanceResourceFiles(s).entrySet()) {
                    Path dest = resourcesOut.resolve(f.getKey());
                    Files.createDirectories(dest.getParent());
                    Files.copy(f.getValue(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    written.add("conformance resource: " + f.getKey());
                }
            }
        }
        return written;
    }

    private static void write(Map<String, Path> files, String namespace, Path out, List<String> written, String label) throws IOException {
        for (Map.Entry<String, Path> f : files.entrySet()) {
            String text = Files.readString(f.getValue(), StandardCharsets.UTF_8);
            if (text.contains("mycelium.harness")) {
                throw new IllegalStateException("Mycelium: a bridge must not depend on the harness: " + f.getValue());
            }
            Path dest = out.resolve(placed(f.getKey(), namespace));
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, rewrite(text, namespace), StandardCharsets.UTF_8);
            written.add(label + placed(f.getKey(), namespace));
        }
    }

    /** The bridges the given contracts bring (their registry rows' {@code bridges}), each once, in the order met. */
    public static List<String> broughtBy(ContractRegistry registry, List<String> contractIds) {
        List<String> slugs = new ArrayList<>();
        if (contractIds.isEmpty()) {
            return slugs; // an empty selection means every contract to the registry, and a project that imports none brings no bridge
        }
        for (ContractRegistry.Entry e : registry.withRequirements(registry.resolve(contractIds))) {
            for (String slug : e.bridges()) {
                if (!slugs.contains(slug)) {
                    slugs.add(slug);
                }
            }
        }
        return slugs;
    }
}
