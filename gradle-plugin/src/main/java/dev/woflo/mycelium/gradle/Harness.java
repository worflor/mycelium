package dev.woflo.mycelium.gradle;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * What every runner shares: the harness's sources ({@code harness/sources.txt}, one definition), and the <em>bundle</em>,
 * the unit an era gate runs: a directory holding {@code classes/} and {@code bundle.properties} (a name, the release
 * the classes were compiled for, and the suite mains to run).
 */
final class Harness {
    private Harness() {
    }

    /** Every {@code .java} file of the shared harness, as {@code harness/sources.txt} lists it. */
    static List<Path> sources(Path root) throws IOException {
        List<Path> dirs = new ArrayList<>();
        for (String line : Files.readAllLines(root.resolve("harness/sources.txt"), StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (!t.isEmpty() && !t.startsWith("#")) {
                dirs.add(root.resolve(t));
            }
        }
        return javaFiles(dirs);
    }

    /** Every {@code .java} file under these files or directories, each once. */
    static List<Path> javaFiles(Collection<Path> roots) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        for (Path r : roots) {
            if (Files.isRegularFile(r) && r.toString().endsWith(".java")) {
                out.add(r.toAbsolutePath().normalize());
            } else if (Files.isDirectory(r)) {
                try (Stream<Path> walk = Files.walk(r)) {
                    walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java"))
                            .sorted()
                            .forEach(p -> out.add(p.toAbsolutePath().normalize()));
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** A bundle's description. */
    record Bundle(Path dir, String name, int release, List<String> mains) {
        Path classes() {
            return dir.resolve("classes");
        }

        static Bundle read(Path dir) throws IOException {
            Properties p = new Properties();
            try (var in = Files.newInputStream(dir.resolve("bundle.properties"))) {
                p.load(in);
            }
            List<String> mains = new ArrayList<>();
            for (String m : p.getProperty("mains", "").split(",")) {
                if (!m.isBlank()) {
                    mains.add(m.strip());
                }
            }
            return new Bundle(dir, p.getProperty("name"), Integer.parseInt(p.getProperty("release")), mains);
        }

        static void write(Path dir, String name, int release, List<String> mains) throws IOException {
            Properties p = new Properties();
            p.setProperty("name", name);
            p.setProperty("release", String.valueOf(release));
            p.setProperty("mains", String.join(",", mains));
            try (OutputStream out = Files.newOutputStream(dir.resolve("bundle.properties"))) {
                p.store(out, "Mycelium bundle: what an era gate runs");
            }
        }
    }

    static void jar(Path classes, Path target) throws IOException {
        try (OutputStream os = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(os);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isRegularFile(p)) {
                    jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, jar);
                    jar.closeEntry();
                }
            }
        }
    }

    /** A bundle's name, filesystem-safe: the same mapping every place a bundle's work directory is named by it. */
    static String slug(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : (Iterable<Path>) walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
