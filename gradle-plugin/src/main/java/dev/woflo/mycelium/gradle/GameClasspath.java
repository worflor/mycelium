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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>Everything lands under the name cache beside the pinned files, keyed by the hashes of the inputs, so a version is opened
 * once. The libraries are keyed by their own hash and shared between versions.
 */
final class GameClasspath {
    private GameClasspath() {
    }

    /** The jars to compile against for {@code entry}: the game's, then the libraries it ships with. */
    static List<Path> of(NameData.Entry entry, NameCache cache, Path dir) throws IOException {
        NameData.Input game = entry.input("game");
        Path archive = cache.get(game);
        boolean renamed = entry.reads().equals("proguard-jar");
        String key = game.sha256() + (renamed ? "-" + entry.input("mappings").sha256() : "");
        Path done = dir.resolve("games").resolve(key + ".list");
        if (Files.isRegularFile(done)) {
            List<Path> jars = new ArrayList<>();
            for (String line : Files.readAllLines(done, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    jars.add(dir.resolve(line));
                }
            }
            if (jars.stream().allMatch(Files::isRegularFile)) {
                return jars;
            }
        }
        Inventory.ProGuard map = null;
        if (renamed) {
            NameData.Input mappings = entry.input("mappings");
            try (InputStream s = Names.open(cache.get(mappings), mappings.entry())) {
                map = Inventory.ProGuard.parse(s);
            }
        }
        List<String> rel = new ArrayList<>();
        Files.createDirectories(dir.resolve("games"));
        Files.createDirectories(dir.resolve("libs"));
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry versions = zip.getEntry("META-INF/versions.list");
            if (versions == null) {
                Path jar = dir.resolve("games").resolve(key + "-0.jar");
                if (map == null) {
                    Files.copy(archive, jar, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    rename(zip, map, jar);
                }
                rel.add("games/" + jar.getFileName());
            } else {
                int n = 0;
                for (String line : lines(zip, versions)) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        Path jar = dir.resolve("games").resolve(key + "-" + n++ + ".jar");
                        try (InputStream in = nested(zip, "META-INF/versions/" + p[2])) {
                            if (map == null) {
                                Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
                            } else {
                                renameStream(in, map, jar);
                            }
                        }
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
                                try (InputStream in = nested(zip, "META-INF/libraries/" + p[2])) {
                                    Path part = Files.createTempFile(dir.resolve("libs"), "lib-", ".part");
                                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                                    Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
                                }
                            }
                            rel.add("libs/" + jar.getFileName());
                        }
                    }
                }
            }
        }
        Files.write(done, rel, StandardCharsets.UTF_8);
        List<Path> jars = new ArrayList<>();
        rel.forEach(r -> jars.add(dir.resolve(r)));
        return jars;
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
