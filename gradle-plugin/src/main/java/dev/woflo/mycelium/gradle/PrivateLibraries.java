package dev.woflo.mycelium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Gives feature libraries artifact-owned identities before bytecode downgrade and loader remapping. */
final class PrivateLibraries {
    private PrivateLibraries() {
    }

    record Relocation(String from, String to) {
        Relocation {
            if (!packageName(from) || !packageName(to) || overlaps(from, to)
                    || to.startsWith("java.") || to.startsWith("javax.")) {
                throw new IllegalArgumentException("privateLibrary needs distinct, non-overlapping Java package prefixes: " + from + " -> " + to);
            }
        }

        String encode() {
            return from + "=" + to;
        }

        static Relocation decode(String encoded) {
            String[] parts = encoded.split("=", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid privateLibrary declaration: " + encoded);
            }
            return new Relocation(parts[0], parts[1]);
        }
    }

    static List<Relocation> declarations(List<String> encoded) {
        List<Relocation> out = new ArrayList<>();
        for (String value : encoded) {
            Relocation next = Relocation.decode(value);
            for (Relocation existing : out) {
                if (overlaps(existing.from(), next.from()) || overlaps(existing.to(), next.to())
                        || overlaps(existing.from(), next.to()) || overlaps(existing.to(), next.from())) {
                    throw new IllegalArgumentException("privateLibrary prefixes overlap: " + existing.encode() + " and " + next.encode());
                }
            }
            out.add(next);
        }
        return out;
    }

    static Map<String, Integer> isolate(Path artifact, List<Path> libraries, List<Relocation> relocations, Path output) throws IOException {
        return isolate(artifact, libraries, relocations, output, 8);
    }

    static Map<String, Integer> isolate(Path artifact, List<Path> libraries, List<Relocation> relocations, Path output, int targetJava) throws IOException {
        if (relocations.isEmpty() || libraries.isEmpty()) {
            throw new IOException("privateLibrary needs both package declarations and library jars");
        }
        Map<String, byte[]> entries = new TreeMap<>();
        read(artifact, entries, false, targetJava);
        List<Path> ordered = libraries.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        for (Path library : ordered) {
            Map<String, byte[]> members = new TreeMap<>();
            read(library, members, true, targetJava);
            for (Map.Entry<String, byte[]> member : members.entrySet()) {
                String name = member.getKey();
                String internal = name.endsWith(".class") ? new ClassReader(member.getValue()).getClassName() : "";
                if (name.endsWith(".class") && relocations.stream().noneMatch(relocation -> under(internal, relocation.from().replace('.', '/'), '/'))) {
                    throw new IOException("private library class has no declared relocation: " + library.getFileName() + "!" + name);
                }
                put(entries, name, member.getValue());
            }
        }
        Remapper remapper = new Remapper() {
            @Override
            public String map(String name) {
                return mapName(name, relocations, '/');
            }

            @Override
            public Object mapValue(Object value) {
                return value instanceof String text ? mapName(text, relocations, '.') : super.mapValue(value);
            }
        };
        Map<String, Integer> counts = new LinkedHashMap<>();
        relocations.forEach(relocation -> counts.put(relocation.from(), 0));
        Map<String, byte[]> rewritten = new TreeMap<>();
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (name.endsWith(".class")) {
                ClassReader reader = new ClassReader(bytes);
                String internal = reader.getClassName();
                String variant = name.startsWith("META-INF/versions/") ? name.substring(0, name.length() - internal.length() - 6) : "";
                if (!name.equals(variant + internal + ".class")) {
                    throw new IOException("private library class path differs from its identity: " + name + " vs " + internal);
                }
                for (Relocation relocation : relocations) {
                    if (under(internal, relocation.from().replace('.', '/'), '/')) {
                        counts.compute(relocation.from(), (key, count) -> count + 1);
                    }
                }
                ClassWriter writer = new ClassWriter(0);
                reader.accept(new ClassRemapper(writer, remapper), 0);
                bytes = writer.toByteArray();
                name = variant + remapper.mapType(internal) + ".class";
            } else if (name.startsWith("META-INF/services/")) {
                name = "META-INF/services/" + mapName(name.substring("META-INF/services/".length()), relocations, '.');
                StringBuilder providers = new StringBuilder();
                for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R", -1)) {
                    int comment = line.indexOf('#');
                    String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
                    if (!provider.isEmpty()) {
                        providers.append(mapName(provider, relocations, '.')).append('\n');
                    }
                }
                bytes = providers.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                name = mapName(name, relocations, '/');
            }
            put(rewritten, name, bytes);
        }
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (count.getValue() == 0) {
                throw new IOException("privateLibrary package has no classes in the supplied jars: " + count.getKey());
            }
        }
        Files.createDirectories(output.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> entry : rewritten.entrySet()) {
                ZipEntry member = new ZipEntry(entry.getKey());
                member.setTime(0);
                zip.putNextEntry(member);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return counts;
    }

    private static void read(Path path, Map<String, byte[]> entries, boolean library, int targetJava) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(path))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (entry.isDirectory() || (name.startsWith("META-INF/versions/") && targetJava < 9)) {
                    continue;
                }
                if (name.equals("module-info.class") || name.endsWith("/module-info.class")) {
                    if (!library && targetJava >= 9) {
                        throw new IOException("privateLibrary cannot merge into an explicit module descriptor: " + path + "!" + name);
                    }
                    continue;
                }
                if (library && name.startsWith("META-INF/")) {
                    String upper = name.toUpperCase(java.util.Locale.ROOT);
                    if (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF") || upper.endsWith(".RSA")
                            || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.equals("META-INF/INDEX.LIST")) {
                        continue;
                    }
                    if (!name.startsWith("META-INF/services/") && !name.startsWith("META-INF/versions/")) {
                        name = "META-INF/private-libraries/" + path.getFileName() + "/" + name.substring("META-INF/".length());
                    }
                }
                if (name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) {
                    throw new IOException("unsafe jar entry in " + path + ": " + name);
                }
                put(entries, name, zip.readAllBytes());
            }
        }
    }

    private static void put(Map<String, byte[]> entries, String name, byte[] bytes) throws IOException {
        byte[] existing = entries.putIfAbsent(name, bytes);
        if (existing != null && !Arrays.equals(existing, bytes)) {
            throw new IOException("private libraries have conflicting jar entry: " + name);
        }
    }

    private static String mapName(String name, List<Relocation> relocations, char separator) {
        for (Relocation relocation : relocations) {
            String from = relocation.from().replace('.', separator);
            if (under(name, from, separator)) {
                return relocation.to().replace('.', separator) + name.substring(from.length());
            }
        }
        return name;
    }

    private static boolean under(String name, String prefix, char separator) {
        return name.equals(prefix) || name.startsWith(prefix + separator);
    }

    private static boolean overlaps(String a, String b) {
        return under(a, b, '.') || under(b, a, '.');
    }

    private static boolean packageName(String name) {
        return name != null && name.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");
    }

    static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
