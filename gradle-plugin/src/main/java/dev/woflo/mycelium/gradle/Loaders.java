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
import java.util.TreeSet;

/**
 * The packages each loader puts in a mod's reach, and the game's own ({@code eras/loaders.json}, docs/eras.md). The pure
 * layer may mention none of them (law P1): {@link PurityTask} forbids {@link #platformPackages()}, so a loader an era names
 * is guarded as soon as its packages are written down here, never by a list kept somewhere else.
 */
public final class Loaders {
    /** One loader: the package roots it puts in reach, and where that fact comes from (for re-verifying it). */
    public record Loader(String name, List<String> packages, String source) {
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
            JsonObject root = new Gson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), JsonObject.class);
            Map<String, Loader> loaders = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("loaders").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                loaders.put(e.getKey(), new Loader(e.getKey(), packages(o, file, e.getKey()), source(o, file, e.getKey())));
            }
            return new Loaders(packages(root.getAsJsonObject("game"), file, "game"),
                    packages(root.getAsJsonObject("tools"), file, "tools"), loaders);
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium loader table not found: " + file, e);
        }
    }

    private static List<String> packages(JsonObject o, File file, String what) {
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

    private static String source(JsonObject o, File file, String what) {
        if (!o.has("source") || o.get("source").getAsString().isBlank()) {
            throw new IllegalStateException(file + ": '" + what + "' does not say where its packages come from");
        }
        return o.get("source").getAsString();
    }

    public Map<String, Loader> loaders() {
        return Map.copyOf(loaders);
    }

    /** Every package root the pure layer may never mention: the game, the tools, every loader. Sorted, each once. */
    public List<String> platformPackages() {
        TreeSet<String> all = new TreeSet<>(game);
        all.addAll(tools);
        loaders.values().forEach(l -> all.addAll(l.packages()));
        return new ArrayList<>(all);
    }
}
