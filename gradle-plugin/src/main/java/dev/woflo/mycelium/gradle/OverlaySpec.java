package dev.woflo.mycelium.gradle;

import org.gradle.api.file.Directory;
import org.gradle.api.provider.ListProperty;

import java.io.File;

/**
 * {@code mycelium { overlays { ... } }}: a resource directory per schema era (docs/decisions/0017). Data and assets change
 * their schema at walls the era table names (dimension types at 26.1, item models at 1.21.4), and a mod keeps one directory
 * per schema. The plugin adds to the resources the directory whose version is the newest at or below the target.
 *
 * <pre>
 * mycelium {
 *     overlays {
 *         base(file("src/main/resources-1211"))                 // every version before the next entry
 *         from("26.1", file("src/main/resources-2601"))         // this version and after
 *     }
 * }
 * </pre>
 */
public abstract class OverlaySpec {
    /** Encoded {@code version|absolute path}, in the order written; the empty version is the base. */
    public abstract ListProperty<String> getEntries();

    /** The directory every version uses until a later entry begins. */
    public void base(Object dir) {
        getEntries().add("|" + path(dir));
    }

    /** The directory this Minecraft version and every later one uses (until a later entry begins). */
    public void from(String minecraft, Object dir) {
        if (minecraft == null || minecraft.isBlank() || minecraft.contains("|")) {
            throw new IllegalArgumentException("Mycelium overlays: from(...) names a Minecraft version");
        }
        getEntries().add(minecraft + "|" + path(dir));
    }

    private static String path(Object dir) {
        if (dir instanceof File f) {
            return f.getAbsolutePath();
        }
        if (dir instanceof Directory d) {
            return d.getAsFile().getAbsolutePath();
        }
        if (dir instanceof CharSequence c) {
            File f = new File(c.toString());
            if (f.isAbsolute()) {
                return f.getPath();
            }
        }
        throw new IllegalArgumentException("Mycelium overlays: give a File or Directory (file(\"...\")), not " + dir);
    }

    /** The directory for {@code minecraft} among {@code entries}, or {@code null} when none begins at or below it. */
    static File choose(java.util.List<String> entries, String minecraft) {
        String best = null;
        String bestFrom = null;
        for (String e : entries) {
            int bar = e.indexOf('|');
            String from = e.substring(0, bar);
            String dir = e.substring(bar + 1);
            if (from.isEmpty()) {
                if (best == null) {
                    best = dir;
                }
            } else if (Eras.compare(minecraft, from) >= 0 && (bestFrom == null || Eras.compare(from, bestFrom) >= 0)) {
                best = dir;
                bestFrom = from;
            }
        }
        return best == null ? null : new File(best);
    }
}
