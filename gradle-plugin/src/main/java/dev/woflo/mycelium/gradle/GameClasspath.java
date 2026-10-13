package dev.woflo.mycelium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * The game as something source can be compiled against (docs/decisions/0016): a version's pinned game archive
 * ({@code eras/names.json}) opened into jars on disk, in the names the entry speaks. A server bundle holds the game's own jar
 * and the libraries it ships with; a game that ships obfuscated is renamed to its official names with Mojang's mappings, as
 * the linkage inventory does ({@link Inventory}). Only the shape of each class is kept (its members and signatures, never its
 * code), because a compiler reads nothing else, and that makes the rename cheap.
 *
 * <p>Everything lands under the name cache beside the pinned files, keyed by the hashes of the inputs <em>and the version of this
 * logic</em> ({@link #LOGIC}: a change to what is written must not be served from a directory the old code filled), so a version is
 * opened once. The libraries are keyed by their own hash and shared between versions.
 *
 * <p>The cache is shared by every Gradle process on a machine (a game server's box has several), so it is written the one way that is
 * safe to read while it is written: every file is written beside its place and moved into it, and the whole fill happens under a
 * file lock, the marker that says a version is done going last. A process that finds the lock taken waits and then finds the version
 * done.
 */
final class GameClasspath {
    private GameClasspath() {
    }

    /** Bump when what {@link #of} writes for the same inputs changes (the rename, what is kept): old directories are then never read. */
    static final int LOGIC = 1;

    private static final Object IN_THIS_PROCESS = new Object();

    /** The jars to compile against for {@code entry}: the game's, then the libraries it ships with. */
    static List<Path> of(NameData.Entry entry, NameCache cache, Path dir) throws IOException {
        NameData.Input game = entry.input("game");
        boolean renamed = entry.reads().equals("proguard-jar");
        String key = "v" + LOGIC + "-" + game.sha256() + (renamed ? "-" + entry.input("mappings").sha256() : "");
        Path done = dir.resolve("games").resolve(key + ".list");
        List<Path> ready = ready(dir, done);
        if (ready != null) {
            return ready;
        }
        Path archive = cache.get(game);
        Files.createDirectories(dir.resolve("games"));
        Files.createDirectories(dir.resolve("libs"));
        synchronized (IN_THIS_PROCESS) {
            try (FileChannel channel = FileChannel.open(dir.resolve("games").resolve(key + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // another process may have filled it while this one waited for the lock
                ready = ready(dir, done);
                if (ready != null) {
                    return ready;
                }
                return fill(entry, cache, dir, archive, key, renamed, done);
            }
        }
    }

    /** The jars of a version that is done, or null: the marker is there and so is every jar it lists. */
    private static List<Path> ready(Path dir, Path done) throws IOException {
        if (!Files.isRegularFile(done)) {
            return null;
        }
        List<Path> jars = new ArrayList<>();
        for (String line : Files.readAllLines(done, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                jars.add(dir.resolve(line));
            }
        }
        return jars.stream().allMatch(Files::isRegularFile) ? jars : null;
    }

    private static List<Path> fill(NameData.Entry entry, NameCache cache, Path dir, Path archive, String key, boolean renamed, Path done) throws IOException {
        Inventory.ProGuard map = null;
        if (renamed) {
            NameData.Input mappings = entry.input("mappings");
            try (InputStream s = Names.open(cache.get(mappings), mappings.entry())) {
                map = Inventory.ProGuard.parse(s);
            }
        }
        List<String> rel = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry versions = zip.getEntry("META-INF/versions.list");
            if (versions == null) {
                Path jar = dir.resolve("games").resolve(key + "-0.jar");
                if (map == null) {
                    Path from = archive;
                    placed(jar, part -> Files.copy(from, part, StandardCopyOption.REPLACE_EXISTING));
                } else {
                    ZipFile source = zip;
                    Inventory.ProGuard m = map;
                    placed(jar, part -> rename(source, m, part));
                }
                rel.add("games/" + jar.getFileName());
            } else {
                int n = 0;
                for (String line : lines(zip, versions)) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        Path jar = dir.resolve("games").resolve(key + "-" + n++ + ".jar");
                        Inventory.ProGuard m = map;
                        String inside = "META-INF/versions/" + p[2];
                        placed(jar, part -> {
                            try (InputStream in = nested(zip, inside)) {
                                if (m == null) {
                                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                                } else {
                                    renameStream(in, m, part);
                                }
                            }
                        });
                        rel.add("games/" + jar.getFileName());
                    }
                }
                ZipEntry libraries = zip.getEntry("META-INF/libraries.list");
                if (libraries != null) {
                    for (String line : lines(zip, libraries)) {
                        String[] p = line.split("\t");
                        if (p.length == 3) {
                            Path jar = dir.resolve("libs").resolve(p[0] + ".jar");
                            if (!Files.isRegularFile(jar)) {
                                String inside = "META-INF/libraries/" + p[2];
                                placed(jar, part -> {
                                    try (InputStream in = nested(zip, inside)) {
                                        Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                });
                            }
                            rel.add("libs/" + jar.getFileName());
                        }
                    }
                }
            }
        }
        // the marker goes last, and goes in whole: a version is done when this file is there
        placed(done, part -> Files.write(part, rel, StandardCharsets.UTF_8));
        List<Path> jars = new ArrayList<>();
        rel.forEach(r -> jars.add(dir.resolve(r)));
        return jars;
    }

    /** What writes a file at the path it is given. */
    @FunctionalInterface
    interface Writer {
        void write(Path to) throws IOException;
    }

    /**
     * Writes {@code target} by writing a file beside it and moving that into place, so no reader and no crashed writer ever sees half a
     * file under the name. The move is atomic where the file system can (and replaces what a dead process left).
     */
    static void placed(Path target, Writer writer) throws IOException {
        Path part = Files.createTempFile(target.getParent(), target.getFileName().toString() + "-", ".part");
        try {
            writer.write(part);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private static InputStream nested(ZipFile zip, String entry) throws IOException {
        ZipEntry e = zip.getEntry(entry);
        if (e == null) {
            throw new IllegalStateException("Mycelium: the bundle lists " + entry + " and does not hold it");
        }
        return zip.getInputStream(e);
    }

    private static List<String> lines(ZipFile zip, ZipEntry entry) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (!line.isBlank()) {
                    out.add(line);
                }
            }
        }
        return out;
    }

    /** A jar renamed to Mojang's official names, shape only. */
    private static void rename(ZipFile jar, Inventory.ProGuard map, Path out) throws IOException {
        try (OutputStream o = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(o)) {
            for (ZipEntry e : java.util.Collections.list(jar.entries())) {
                if (!e.isDirectory() && e.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(e)) {
                        put(z, in.readAllBytes(), map);
                    }
                }
            }
        }
    }

    private static void renameStream(InputStream nestedJar, Inventory.ProGuard map, Path out) throws IOException {
        try (OutputStream o = Files.newOutputStream(out); ZipOutputStream z = new ZipOutputStream(o);
             java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(nestedJar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory() && e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")) {
                    put(z, in.readAllBytes(), map);
                }
            }
        }
    }

    private static void put(ZipOutputStream z, byte[] bytes, Inventory.ProGuard map) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        var remapper = map.remapper();
        reader.accept(new ClassRemapper(writer, remapper), ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        z.putNextEntry(new ZipEntry(remapper.map(reader.getClassName()) + ".class"));
        z.write(writer.toByteArray());
        z.closeEntry();
    }
}
