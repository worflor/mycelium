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
import java.util.TreeSet;

/**
 * The packages each loader puts in a mod's reach, and the game's own ({@code eras/loaders.json}, docs/eras.md). The pure
 * layer may mention none of them (law P1): {@link PurityTask} forbids {@link #platformPackages()}, so a loader an era names
 * is guarded as soon as its packages are written down here, never by a list kept somewhere else.
 *
 * <p>The table also says what the plugin may know about a loader without knowing the loader: its {@code family}, and
 * where a built artifact declares the Minecraft versions the loader will accept ({@link Compat}), as data the range gate
 * reads ({@code docs/decisions/0009}).
 */
public final class Loaders {
    /** What kinds of artifact a loader runs. */
    public static final Set<String> FAMILIES = Set.of("mod", "plugin", "agent");

    /** The formats a {@link Compat} file may be in, and the syntaxes its value may be written in. */
    public static final Set<String> FORMATS = Set.of("json", "toml", "yaml");
    public static final Set<String> SYNTAXES = Set.of("semver", "maven", "api-version");

    /**
     * Where a built artifact declares the Minecraft versions it accepts: {@code files} (the first that exists, as paths
     * in the artifact's resources), the {@code format} they are in, how to find the value ({@code path} for json,
     * {@code table}, {@code where} and {@code key} for toml, {@code key} for yaml) and the {@code syntax} it is written in.
     * {@code from} is the first Minecraft version whose artifacts declare it there, with {@code before} saying where an
     * older artifact declares it instead (code the build does not read); both are {@code null} when the file serves every
     * version the loader exists on.
     */
    public record Compat(List<String> files, String format, String path, String table, Map<String, String> where, String key,
                         String syntax, String from, String before) {
    }

    /**
     * How a loader keeps fluids in block storage: the name of its unit and how many of it make a bucket (Fabric's droplets,
     * 81,000; Forge's and NeoForge's millibuckets, 1,000), and where that is read. {@code storage.access@1} converts its declared
     * unit, thousandths of a bucket, to this one exactly at the seam; a loader with no fluid storage API has none.
     */
    public record Fluid(String unit, long perBucket, String source) {
    }

    /** What a {@link Fluid} row may say. */
    public static final Set<String> FLUID_KEYS = Set.of("unit", "perBucket", "source");

    /**
     * One loader: its family, the package roots it puts in reach, where that fact comes from, where it declares compatibility,
     * and how it keeps fluids ({@code null}: it has no fluid storage a mod can reach).
     */
    public record Loader(String name, String family, List<String> packages, String source, Compat compat, Fluid fluid) {
    }

    private final List<String> game;
    private final List<String> tools;
    private final Map<String, Loader> loaders;

    private Loaders(List<String> game, List<String> tools, Map<String, Loader> loaders) {
        this.game = game;
        this.tools = tools;
        this.loaders = loaders;
    }

    public static Loaders load(File myceliumRoot) {
        File file = new File(myceliumRoot, "eras/loaders.json");
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium loader table not found: " + file, e);
        }
    }

    /** The loader table in {@code text}; {@code file} names it in a refusal. */
    static Loaders parse(String text, File file) {
        return parse(text, file.toString());
    }

    static Loaders parse(String text, String file) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        if (!root.has("schema") || root.get("schema").getAsInt() != 2) {
            throw new IllegalStateException(file + ": schema must be 2 (every loader has a family)");
        }
        Map<String, Loader> loaders = new LinkedHashMap<>();
        Map<String, String> borrowed = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("loaders").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String name = e.getKey();
            String family = o.has("family") ? o.get("family").getAsString() : "";
            if (!FAMILIES.contains(family)) {
                throw new IllegalStateException(file + ": '" + name + "' has family '" + family + "'; a loader is one of " + FAMILIES);
            }
            if (o.has("compat") && o.has("compatFrom")) {
                throw new IllegalStateException(file + ": '" + name + "' declares its own compat and borrows another's");
            }
            Compat compat = o.has("compat") ? compat(o.getAsJsonObject("compat"), file, name) : null;
            if (o.has("compatFrom")) {
                borrowed.put(name, o.get("compatFrom").getAsString());
            }
            Fluid fluid = o.has("fluid") ? fluid(o.get("fluid"), file, name) : null;
            loaders.put(name, new Loader(name, family, packages(o, file, name), source(o, file, name), compat, fluid));
        }
        for (Map.Entry<String, String> b : borrowed.entrySet()) {
            Loader from = loaders.get(b.getValue());
            if (from == null || from.compat() == null) {
                throw new IllegalStateException(file + ": '" + b.getKey() + "' borrows the compat of '" + b.getValue()
                        + "', which declares none of its own");
            }
            Loader own = loaders.get(b.getKey());
            loaders.put(b.getKey(), new Loader(own.name(), own.family(), own.packages(), own.source(), from.compat(), own.fluid()));
        }
        return new Loaders(packages(root.getAsJsonObject("game"), file, "game"),
                packages(root.getAsJsonObject("tools"), file, "tools"), loaders);
    }

    private static Compat compat(JsonObject c, String file, String loader) {
        List<String> files = new ArrayList<>();
        if (c.has("file")) {
            c.getAsJsonArray("file").forEach(x -> files.add(x.getAsString()));
        }
        String format = c.has("format") ? c.get("format").getAsString() : "";
        String syntax = c.has("syntax") ? c.get("syntax").getAsString() : "";
        if (files.isEmpty() || !FORMATS.contains(format) || !SYNTAXES.contains(syntax)) {
            throw new IllegalStateException(file + ": '" + loader + "' compat needs file(s), a format of " + FORMATS
                    + " and a syntax of " + SYNTAXES);
        }
        String path = c.has("path") ? c.get("path").getAsString() : null;
        String table = c.has("table") ? c.get("table").getAsString() : null;
        String key = c.has("key") ? c.get("key").getAsString() : null;
        Map<String, String> where = new LinkedHashMap<>();
        if (c.has("where")) {
            c.getAsJsonObject("where").entrySet().forEach(e -> where.put(e.getKey(), e.getValue().getAsString()));
        }
        boolean complete = switch (format) {
            case "json" -> path != null;
            case "toml" -> table != null && key != null;
            default -> key != null;
        };
        if (!complete) {
            throw new IllegalStateException(file + ": '" + loader + "' compat in " + format + " needs "
                    + (format.equals("json") ? "a path" : format.equals("toml") ? "a table and a key" : "a key"));
        }
        String from = c.has("from") ? c.get("from").getAsString() : null;
        String before = c.has("before") ? c.get("before").getAsString() : null;
        if ((from == null) != (before == null)) {
            throw new IllegalStateException(file + ": '" + loader + "' compat has a 'from' or a 'before' without the other:"
                    + " an older artifact declares its versions somewhere else, and the table says where");
        }
        return new Compat(files, format, path, table, where, key, syntax, from, before);
    }

    private static Fluid fluid(JsonElement e, String file, String loader) {
        if (!e.isJsonObject()) {
            throw new IllegalStateException(file + ": '" + loader + "' fluid is {\"unit\": name, \"perBucket\": n, \"source\": where}");
        }
        JsonObject f = e.getAsJsonObject();
        for (String key : f.keySet()) {
            if (!FLUID_KEYS.contains(key)) {
                throw new IllegalStateException(file + ": '" + loader + "' fluid has unit, perBucket and source, not '" + key + "'");
            }
        }
        String unit = f.has("unit") ? f.get("unit").getAsString() : "";
        String source = f.has("source") ? f.get("source").getAsString() : "";
        long perBucket = f.has("perBucket") && f.get("perBucket").isJsonPrimitive() && f.get("perBucket").getAsJsonPrimitive().isNumber()
                ? f.get("perBucket").getAsLong() : 0;
        if (unit.isBlank() || source.isBlank() || perBucket < 1) {
            throw new IllegalStateException(file + ": '" + loader + "' fluid needs a unit, a whole perBucket of at least 1 and the"
                    + " source it was read from");
        }
        return new Fluid(unit, perBucket, source);
    }

    private static List<String> packages(JsonObject o, String file, String what) {
        List<String> out = new ArrayList<>();
        if (o == null || !o.has("packages")) {
            throw new IllegalStateException(file + ": '" + what + "' names no packages");
        }
        o.getAsJsonArray("packages").forEach(x -> out.add(x.getAsString()));
        if (out.isEmpty()) {
            throw new IllegalStateException(file + ": '" + what + "' names no packages");
        }
        return out;
    }

    private static String source(JsonObject o, String file, String what) {
        if (!o.has("source") || o.get("source").getAsString().isBlank()) {
            throw new IllegalStateException(file + ": '" + what + "' does not say where its packages come from");
        }
        return o.get("source").getAsString();
    }

    public Map<String, Loader> loaders() {
        return Map.copyOf(loaders);
    }

    /**
     * How many of a loader's fluid units make a bucket ({@code fluid.perBucket}): what {@code storage.access@1}'s seam converts
     * to on that loader. A loader the table does not know, or one with no fluid storage, is refused by name.
     */
    public long fluidPerBucket(String loader) {
        Loader l = loaders.get(loader);
        if (l == null) {
            throw new IllegalStateException("'" + loader + "' is not one eras/loaders.json knows (it knows " + new TreeSet<>(loaders.keySet()) + ")");
        }
        if (l.fluid() == null) {
            throw new IllegalStateException("'" + loader + "' keeps no fluid storage a mod can reach (eras/loaders.json has no fluid row for it)");
        }
        return l.fluid().perBucket();
    }

    /** Every package root the pure layer may never mention: the game, the tools, every loader. Sorted, each once. */
    public List<String> platformPackages() {
        TreeSet<String> all = new TreeSet<>(game);
        all.addAll(tools);
        loaders.values().forEach(l -> all.addAll(l.packages()));
        return new ArrayList<>(all);
    }
}
