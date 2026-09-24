package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Every Minecraft version by era ({@code eras/minecraft.json}, docs/eras.md): its Java, its loaders, its names and the
 * walls a port crosses. A consumer names its Minecraft version; everything that depends on the era follows from here.
 */
public final class Eras {
    public record Era(String id, String from, String to, int java, List<String> loaders, String names, List<String> walls) {
    }

    private final List<Era> eras;

    private Eras(List<Era> eras) {
        this.eras = eras;
    }

    public static Eras load(File myceliumRoot) {
        File file = new File(myceliumRoot, "eras/minecraft.json");
        try {
            JsonObject root = new Gson().fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), JsonObject.class);
            List<Era> out = new ArrayList<>();
            for (JsonElement e : root.getAsJsonArray("eras")) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Era(o.get("id").getAsString(), o.get("from").getAsString(), o.get("to").getAsString(),
                        o.get("java").getAsInt(), strings(o, "loaders"), o.get("names").getAsString(), strings(o, "walls")));
            }
            return new Eras(out);
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium era table not found: " + file, e);
        }
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
            if (compare(minecraft, era.from()) >= 0 && compare(minecraft, era.to()) <= 0) {
                return era;
            }
        }
        throw new IllegalArgumentException("Mycelium: no era for Minecraft '" + minecraft
                + "' in eras/minecraft.json (release, b/a/c versions; weekly snapshots are not targets)");
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
}
