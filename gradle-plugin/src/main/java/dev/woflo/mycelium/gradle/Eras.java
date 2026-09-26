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
import java.util.List;
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
    public record Era(String id, String from, String to, int java, List<String> loaders, String names, List<String> walls) {
        /** Whether this era covers {@code minecraft}. */
        public boolean covers(String minecraft) {
            return atLeast(minecraft, from) && atMost(minecraft, to);
        }
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
