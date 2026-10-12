package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Every Minecraft version by era ({@code eras/minecraft.json}, docs/eras.md): its Java, its loaders, its names and the
 * walls a port crosses. A consumer names its Minecraft version; everything that depends on the era follows from here.
 *
 * <p><b>Bounds.</b> An era covers every version {@code v} with {@code from <= v <= to}, both ends included. A bound is
 * either a version as Mojang's launcher manifest names it ({@code 1.20.5}, {@code b1.0}, {@code c0.30_01c}), or a whole
 * line, {@code <line>.x} ({@code 26.x}, {@code 1.21.x}, {@code b1.x}): every version whose leading components are the
 * line's, whatever follows. A line is how an era says "and every version of this line still to come" without naming a
 * version that does not exist. {@code eras/versions.json} holds the real versions; the repository check proves every
 * named bound is one of them and every real version falls in exactly one era.
 *
 * <p><b>Order.</b> Classic, then alpha, then beta, then releases (1.x before 26.x). A pre-release or release-candidate
 * suffix ({@code -pre1}, {@code -rc1}) is ignored: a pre-release belongs to the era of the release it precedes. Weekly
 * snapshots ({@code 24w14a}) and the versions before Classic ({@code rd-}, {@code in-}, {@code inf-}) are not targets.
 */
public final class Eras {
    /**
     * What code can be called: {@code mojang} (the official names), {@code mojang-from-1.14.4} (official from there, MCP
     * or Yarn's before), {@code mcp}, {@code srg}, {@code feather}, {@code intermediary}, {@code calamus},
     * {@code obfuscated}, {@code unobfuscated} (the game ships its own names), {@code spigot} and {@code craftbukkit}
     * (a server's remapping of them) and {@code api} (a published API, no game names at all).
     */
    public static final java.util.Set<String> NAMES = java.util.Set.of("mojang", "mojang-from-1.14.4", "mcp", "srg", "feather",
            "intermediary", "calamus", "obfuscated", "unobfuscated", "spigot", "craftbukkit", "api");

    /** One value, from the version it begins to apply on ({@code null}: the era's start) until the next step. */
    public record Step(String from, String value) {
    }

    /**
     * How an era's code is named. {@code dev} is what the platform layer's source is written in (a plugin's is always the
     * API's own, whatever this says); {@code runtime} is what the running game is called, per loader ({@code "*"} for the
     * loaders it does not name), as steps when it changes inside the era.
     */
    public record Names(String dev, Map<String, List<Step>> runtime) {
        /** What {@code loader}'s running game is called on {@code minecraft}, or {@code null} when the era says nothing. */
        public String runtimeFor(String loader, String minecraft) {
            List<Step> steps = runtime.containsKey(loader) ? runtime.get(loader) : runtime.get("*");
            return steps == null ? null : at(steps, minecraft);
        }
    }

    /**
     * A wall a port crosses: {@code at} is the first version of its far side, {@code appliesTo} the loaders or families
     * ({@code mod}, {@code plugin}) it splits (empty: every artifact), {@code what} what changes.
     */
    public record Wall(String at, List<String> appliesTo, String what) {
        /** Whether this wall splits an artifact of {@code loader}, which belongs to {@code family}. */
        public boolean splits(String loader, String family) {
            return appliesTo.isEmpty() || appliesTo.contains(loader) || appliesTo.contains(family);
        }
    }

    public record Era(String id, String from, String to, int java, List<String> loaders, Names names, List<Wall> walls,
                      List<String> notes, Map<String, String> since, Map<String, List<Step>> outerJava) {
        /** Whether this era covers {@code minecraft}. */
        public boolean covers(String minecraft) {
            return atLeast(minecraft, from) && atMost(minecraft, to);
        }

        /** Whether {@code loader} exists on {@code minecraft} (an era this covers): listed, and past its first version. */
        public boolean offers(String loader, String minecraft) {
            return loaders.contains(loader) && (!since.containsKey(loader) || compare(minecraft, since.get(loader)) >= 0);
        }

        /**
         * The Java feature version of the newest class file {@code loader}'s scanner reads on {@code minecraft}: where
         * the era names none, the era's own Java (a scanner that reads what the game runs on is no ceiling).
         */
        public int outerJavaFor(String loader, String minecraft) {
            List<Step> steps = outerJava.get(loader);
            return steps == null ? java : Integer.parseInt(at(steps, minecraft));
        }
    }

    /** The value of the last step that has begun on {@code minecraft}; the first step's when none has. */
    static String at(List<Step> steps, String minecraft) {
        String value = steps.get(0).value();
        for (Step s : steps) {
            if (s.from() == null || compare(minecraft, s.from()) >= 0) {
                value = s.value();
            }
        }
        return value;
    }

    /** The mechanisms a version can draw an item's state with ({@code presentation} rows of the era table). */
    public static final java.util.Set<String> REALIZATIONS = java.util.Set.of("item-definition", "model-overrides", "none");

    /**
     * A range of versions and the mechanism they draw an item's state with (docs/decisions/0012): its own table, because
     * the ranges cross the eras (model overrides run from 1.14 to 1.21.3, through four eras). Bounds are as an era's are.
     */
    public record Presentation(String from, String to, String realization, String what) {
        public boolean covers(String minecraft) {
            return atLeast(minecraft, from) && atMost(minecraft, to);
        }
    }

    private final List<Era> eras;
    private final List<Presentation> presentation;

    private Eras(List<Era> eras, List<Presentation> presentation) {
        this.eras = eras;
        this.presentation = presentation;
    }

    public static Eras load(File myceliumRoot) {
        File file = new File(myceliumRoot, "eras/minecraft.json");
        try {
            return parse(Files.readString(file.toPath(), StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium era table not found: " + file, e);
        }
    }

    /** The era table in {@code text}; {@code where} names it in a refusal. A malformed row is refused, by name. */
    static Eras parse(String text, String where) {
        JsonObject root = new Gson().fromJson(text, JsonObject.class);
        if (!root.has("schema") || root.get("schema").getAsInt() != 2) {
            throw new IllegalStateException(where + ": schema must be 2 (names are a pair, walls are objects)");
        }
        List<Era> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("eras")) {
            JsonObject o = e.getAsJsonObject();
            String id = o.get("id").getAsString();
            out.add(new Era(id, o.get("from").getAsString(), o.get("to").getAsString(), o.get("java").getAsInt(),
                    strings(o, "loaders"), names(o, id, where), walls(o, id, where), strings(o, "notes"), since(o),
                    outerJava(o, id, where)));
        }
        return new Eras(out, presentation(root, where));
    }

    private static List<Presentation> presentation(JsonObject root, String where) {
        List<Presentation> out = new ArrayList<>();
        if (!root.has("presentation")) {
            return out;
        }
        for (JsonElement e : root.getAsJsonArray("presentation")) {
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
            for (String key : o.keySet()) {
                if (!List.of("from", "to", "realization", "what").contains(key)) {
                    throw new IllegalStateException(where + ": a presentation row has from, to, realization and what, not '" + key + "'");
                }
            }
            if (!o.has("from") || !o.has("to") || !o.has("realization")) {
                throw new IllegalStateException(where + ": a presentation row is {\"from\": version, \"to\": version, \"realization\": name}, not " + e);
            }
            String realization = o.get("realization").getAsString();
            if (!REALIZATIONS.contains(realization)) {
                throw new IllegalStateException(where + ": presentation realization '" + realization + "' is not one of " + new TreeSet<>(REALIZATIONS));
            }
            out.add(new Presentation(o.get("from").getAsString(), o.get("to").getAsString(), realization,
                    o.has("what") ? o.get("what").getAsString() : ""));
        }
        return out;
    }

    private static Names names(JsonObject era, String id, String where) {
        if (!era.has("names") || !era.get("names").isJsonObject() || !era.getAsJsonObject("names").has("dev")
                || !era.getAsJsonObject("names").has("runtime")) {
            throw new IllegalStateException(where + ": era '" + id + "': names must be a pair {\"dev\": ..., \"runtime\": {...}}");
        }
        JsonObject n = era.getAsJsonObject("names");
        Map<String, List<Step>> runtime = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> r : n.getAsJsonObject("runtime").entrySet()) {
            runtime.put(r.getKey(), steps(r.getValue(), "names", id, where));
        }
        return new Names(n.get("dev").getAsString(), runtime);
    }

    /** A plain string is one step from the era's start; an array holds {@code {"from": version, <key>: value}} steps. */
    private static List<Step> steps(JsonElement value, String key, String id, String where) {
        List<Step> out = new ArrayList<>();
        if (value.isJsonPrimitive()) {
            out.add(new Step(null, value.getAsString()));
            return out;
        }
        String field = key.equals("outerJava") ? "java" : "names";
        for (JsonElement s : value.getAsJsonArray()) {
            JsonObject o = s.getAsJsonObject();
            if (!o.has("from") || !o.has(field)) {
                throw new IllegalStateException(where + ": era '" + id + "': each " + key + " step needs 'from' and '" + field + "'");
            }
            out.add(new Step(o.get("from").getAsString(), o.get(field).getAsString()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(where + ": era '" + id + "': an empty " + key + " step list");
        }
        return out;
    }

    private static List<Wall> walls(JsonObject era, String id, String where) {
        List<Wall> out = new ArrayList<>();
        if (!era.has("walls")) {
            return out;
        }
        for (JsonElement w : era.getAsJsonArray("walls")) {
            if (!w.isJsonObject() || !w.getAsJsonObject().has("at") || !w.getAsJsonObject().has("what")) {
                throw new IllegalStateException(where + ": era '" + id + "': a wall is {\"at\": version, \"what\": text}, not " + w);
            }
            JsonObject o = w.getAsJsonObject();
            out.add(new Wall(o.get("at").getAsString(), strings(o, "for"), o.get("what").getAsString()));
        }
        return out;
    }

    private static Map<String, String> since(JsonObject era) {
        Map<String, String> out = new LinkedHashMap<>();
        if (era.has("since")) {
            era.getAsJsonObject("since").entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsString()));
        }
        return out;
    }

    private static Map<String, List<Step>> outerJava(JsonObject era, String id, String where) {
        Map<String, List<Step>> out = new LinkedHashMap<>();
        if (era.has("outerJava")) {
            for (Map.Entry<String, JsonElement> e : era.getAsJsonObject("outerJava").entrySet()) {
                out.put(e.getKey(), steps(e.getValue(), "outerJava", id, where));
            }
        }
        return out;
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key)) {
            o.getAsJsonArray(key).forEach(x -> out.add(x.getAsString()));
        }
        return out;
    }

    /** The era of a Minecraft version ({@code 26.3}, {@code 1.21.1}, {@code b1.7.3}, {@code 1.20.5-pre1}). */
    public Era of(String minecraft) {
        for (Era era : eras) {
            if (era.covers(minecraft)) {
                return era;
            }
        }
        throw new IllegalArgumentException("Mycelium: no era covers Minecraft '" + minecraft + "' (eras/minecraft.json). "
                + nearest(minecraft) + " Targets are releases, betas, alphas and Classic versions as the launcher names them;"
                + " weekly snapshots and the versions before Classic are not.");
    }

    /** Which eras a version falls between, for a miss: the nearest below and the nearest above, where there are such. */
    private String nearest(String minecraft) {
        Era below = null;
        Era above = null;
        for (Era era : eras) {
            if (!atMost(minecraft, era.to()) && (below == null || compare(lineStart(era.to()), lineStart(below.to())) > 0)) {
                below = era;
            }
            if (!atLeast(minecraft, era.from()) && (above == null || compare(lineStart(era.from()), lineStart(above.from())) < 0)) {
                above = era;
            }
        }
        StringBuilder s = new StringBuilder("It is");
        if (below != null) {
            s.append(" after '").append(below.id()).append("' (to ").append(below.to()).append(')');
        }
        if (below != null && above != null) {
            s.append(" and");
        }
        if (above != null) {
            s.append(" before '").append(above.id()).append("' (from ").append(above.from()).append(')');
        }
        return below == null && above == null ? "No era is near it." : s.append('.').toString();
    }

    /** Every Java some era runs on: the set a framework proves itself on. */
    public List<Integer> allJavas() {
        TreeSet<Integer> javas = new TreeSet<>();
        eras.forEach(e -> javas.add(e.java()));
        return new ArrayList<>(javas);
    }

    public List<Era> all() {
        return List.copyOf(eras);
    }

    public List<Presentation> presentation() {
        return List.copyOf(presentation);
    }

    /** The realization a Minecraft version draws an item's state with; a version no row covers is a refusal that says so. */
    public String presentationFor(String minecraft) {
        for (Presentation p : presentation) {
            if (p.covers(minecraft)) {
                return p.realization();
            }
        }
        throw new IllegalArgumentException("Mycelium: no presentation row covers Minecraft '" + minecraft + "' (eras/minecraft.json, \"presentation\")");
    }

    /** Whether a bound names a whole line ({@code 26.x}) rather than one version. */
    public static boolean isLine(String bound) {
        return bound.endsWith(".x");
    }

    /** {@code v >= bound}: a line bound starts at the line's first version. */
    static boolean atLeast(String v, String bound) {
        if (!isLine(bound)) {
            return compare(v, bound) >= 0;
        }
        return compareToLine(v, bound) >= 0;
    }

    /** {@code v <= bound}: a line bound ends after the line's last version, whatever it turns out to be. */
    static boolean atMost(String v, String bound) {
        if (!isLine(bound)) {
            return compare(v, bound) <= 0;
        }
        return compareToLine(v, bound) <= 0;
    }

    /** A line bound as a version to order bounds by: {@code 1.21.x} as {@code 1.21}. */
    static String lineStart(String bound) {
        return isLine(bound) ? bound.substring(0, bound.length() - 2) : bound;
    }

    /** Orders a version against a line: 0 when the version is on the line, else as its leading components compare. */
    private static int compareToLine(String v, String line) {
        String prefix = lineStart(line);
        int pv = phase(v);
        int pl = phase(prefix);
        if (pv != pl) {
            return Integer.compare(pv, pl);
        }
        int[] x = numbers(v);
        int[] y = numbers(prefix);
        for (int i = 0; i < y.length; i++) {
            int u = i < x.length ? x[i] : 0;
            if (u != y[i]) {
                return Integer.compare(u, y[i]);
            }
        }
        return 0;
    }

    /** Orders versions: classic, then alpha, then beta, then releases (1.x before 26.x); a pre-release suffix is ignored. */
    static int compare(String a, String b) {
        int pa = phase(a);
        int pb = phase(b);
        if (pa != pb) {
            return Integer.compare(pa, pb);
        }
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? x[i] : 0;
            int v = i < y.length ? y[i] : 0;
            if (u != v) {
                return Integer.compare(u, v);
            }
        }
        return 0;
    }

    /** Whether {@link #compare} can read this id at all (the pre-Classic {@code rd-}/{@code in-}/{@code inf-} ids it cannot). */
    public static boolean readable(String v) {
        try {
            numbers(v);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int phase(String v) {
        return switch (v.charAt(0)) {
            case 'c' -> 0;
            case 'a' -> 1;
            case 'b' -> 2;
            default -> 3;
        };
    }

    private static int[] numbers(String v) {
        String core = v.replaceFirst("^[cab]", "");
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9].*$", "");
            if (digits.isEmpty()) {
                throw new IllegalArgumentException("Mycelium: not a Minecraft version: " + v);
            }
            out[i] = Integer.parseInt(digits);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Eras" + Arrays.toString(eras.stream().map(Era::id).toArray());
    }
}
