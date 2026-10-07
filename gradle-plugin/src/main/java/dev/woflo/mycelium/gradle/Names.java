package dev.woflo.mycelium.gradle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * What the game has under one naming on one version: the classes, methods and fields a reference can be held to
 * (docs/decisions/0010). A reference is {@link Verdict#PRESENT}, {@link Verdict#ABSENT}, or something the data cannot
 * say ({@link Verdict#UNKNOWN}): a member whose name the data does not number (a constructor, an enum's {@code values},
 * a method inherited from outside the game) is never counted as found or as missing.
 */
interface Names {
    enum Verdict { PRESENT, ABSENT, UNKNOWN }

    /** Whether the game has the class (an internal name, {@code net/minecraft/world/World}). */
    Verdict hasClass(String internalName);

    /**
     * Whether the game has the method, named on the first of {@code owners} that the data can answer for (a member an
     * artifact's own subclass inherits is named on that subclass, and its superclasses follow it).
     */
    Verdict hasMethod(List<String> owners, String name, String descriptor);

    Verdict hasField(List<String> owners, String name, String descriptor);

    /** A line for the report: what the data is and how much of it there is. */
    String describe();

    // --- flat names: Searge's joined.srg and Tiny files ----------------------------------------------------------------

    /**
     * Names the data numbers ({@code func_123_a}, {@code method_8320}) are held to existing anywhere in the game, whatever
     * their owner: a numbered name is one method's identity across the class hierarchy, so a subclass that names it as
     * inherited is no different from the class that declares it. Every other member is {@code UNKNOWN}.
     */
    final class Flat implements Names {
        private final String label;
        private final String methodPrefix;
        private final String fieldPrefix;
        private final boolean fieldDescriptors;
        private final Set<String> classes = new HashSet<>();
        private final Set<String> methods = new HashSet<>();
        private final Set<String> fields = new HashSet<>();

        Flat(String label, String methodPrefix, String fieldPrefix, boolean fieldDescriptors) {
            this.label = label;
            this.methodPrefix = methodPrefix;
            this.fieldPrefix = fieldPrefix;
            this.fieldDescriptors = fieldDescriptors;
        }

        @Override
        public Verdict hasClass(String name) {
            return classes.contains(name) ? Verdict.PRESENT : Verdict.ABSENT;
        }

        @Override
        public Verdict hasMethod(List<String> owners, String name, String descriptor) {
            if (!name.startsWith(methodPrefix)) {
                return Verdict.UNKNOWN;
            }
            return methods.contains(name + " " + descriptor) ? Verdict.PRESENT : Verdict.ABSENT;
        }

        @Override
        public Verdict hasField(List<String> owners, String name, String descriptor) {
            if (!name.startsWith(fieldPrefix)) {
                return Verdict.UNKNOWN;
            }
            return fields.contains(fieldDescriptors ? name + " " + descriptor : name) ? Verdict.PRESENT : Verdict.ABSENT;
        }

        @Override
        public String describe() {
            return label + ": " + classes.size() + " classes, " + methods.size() + " numbered methods, " + fields.size() + " numbered fields";
        }

        /**
         * Searge's {@code joined.srg} ({@code CL: obf srg}, {@code FD: obf/name srg/name}, {@code MD: obf/name obfDesc srg/name srgDesc}):
         * the names after the arrow are the ones a Forge mod's jar runs on.
         */
        static Flat srg(String label, InputStream in) throws IOException {
            Flat out = new Flat(label, "func_", "field_", false);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    String[] p = line.split(" ");
                    if (line.startsWith("CL: ") && p.length == 3) {
                        out.classes.add(p[2]);
                    } else if (line.startsWith("MD: ") && p.length == 5) {
                        out.methods.add(p[3].substring(p[3].lastIndexOf('/') + 1) + " " + p[4]);
                    } else if (line.startsWith("FD: ") && p.length == 3) {
                        out.fields.add(p[2].substring(p[2].lastIndexOf('/') + 1));
                    }
                }
            }
            if (out.classes.isEmpty()) {
                throw new IllegalStateException("Mycelium: " + label + " has no class rows: it is not a joined.srg");
            }
            return out;
        }

        /**
         * A Tiny v2 file, {@code to} being the column of names the artifact runs on ({@code intermediary}): the file's descriptors
         * are in its first column's class names, so they are written again in {@code to}'s.
         */
        static Flat tiny(String label, InputStream in, String to) throws IOException {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    lines.add(line);
                }
            }
            if (lines.isEmpty()) {
                throw new IllegalStateException("Mycelium: " + label + " is empty: it is not a Tiny file");
            }
            String[] header = lines.get(0).split("\t", -1);
            if (header.length < 5 || !header[0].equals("tiny") || !header[1].equals("2")) {
                throw new IllegalStateException("Mycelium: " + label + " is not a Tiny v2 file (its header is '" + lines.get(0) + "')");
            }
            List<String> columns = List.of(header).subList(3, header.length);
            int target = columns.indexOf(to);
            if (target < 1) {
                throw new IllegalStateException("Mycelium: " + label + " has the names " + columns + ", and not '" + to + "' after the first");
            }
            Map<String, String> classMap = new HashMap<>();
            for (String line : lines) {
                if (line.startsWith("c\t")) {
                    String[] c = line.split("\t", -1);
                    classMap.put(c[1], c[1 + target].isEmpty() ? c[1] : c[1 + target]);
                }
            }
            Flat out = new Flat(label, "method_", "field_", true);
            for (String line : lines) {
                if (line.startsWith("c\t")) {
                    String[] c = line.split("\t", -1);
                    out.classes.add(c[1 + target].isEmpty() ? c[1] : c[1 + target]);
                } else if (line.startsWith("\tm\t") || line.startsWith("\tf\t")) {
                    String[] m = line.split("\t", -1);
                    String name = m[3 + target].isEmpty() ? m[3] : m[3 + target];
                    String key = name + " " + remap(m[2], classMap);
                    (line.charAt(1) == 'm' ? out.methods : out.fields).add(key);
                }
            }
            if (out.classes.isEmpty()) {
                throw new IllegalStateException("Mycelium: " + label + " has no class rows");
            }
            return out;
        }

        /** A descriptor with each class name replaced by its name in {@code map} (the ones the map lacks stay). */
        static String remap(String descriptor, Map<String, String> map) {
            StringBuilder out = new StringBuilder(descriptor.length() + 16);
            for (int i = 0; i < descriptor.length(); i++) {
                char c = descriptor.charAt(i);
                out.append(c);
                if (c == 'L') {
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) {
                        out.append(descriptor, i + 1, descriptor.length());
                        break;
                    }
                    String name = descriptor.substring(i + 1, end);
                    out.append(map.getOrDefault(name, name)).append(';');
                    i = end;
                }
            }
            return out.toString();
        }
    }

    /** The game's names as {@code entry} pins them, read from the inputs {@code cache} holds (and fetches when it lacks them). */
    static Names read(NameData.Entry entry, NameCache cache) throws IOException {
        String label = entry.namespace() + " " + entry.version();
        switch (entry.reads()) {
            case "srg-flat": {
                NameData.Input in = entry.input("joined");
                try (InputStream s = open(cache.get(in), in.entry())) {
                    return Flat.srg(label, s);
                }
            }
            case "tiny-flat": {
                NameData.Input in = entry.input("mappings");
                try (InputStream s = open(cache.get(in), in.entry())) {
                    return Flat.tiny(label, s, entry.namespace());
                }
            }
            case "jar": {
                return Inventory.of(label, cache.get(entry.input("game")), entry.owns());
            }
            case "proguard-jar": {
                NameData.Input mappings = entry.input("mappings");
                try (InputStream s = open(cache.get(mappings), mappings.entry())) {
                    return Inventory.of(label, cache.get(entry.input("game")), s, entry.owns());
                }
            }
            default:
                throw new IllegalStateException("Mycelium: no reader for '" + entry.reads() + "' (" + label + ")");
        }
    }

    /** The named entry of an archive, or the file itself when {@code entry} is {@code null}. */
    static InputStream open(Path file, String entry) throws IOException {
        if (entry == null) {
            return Files.newInputStream(file);
        }
        ZipFile zip = new ZipFile(file.toFile());
        ZipEntry e = zip.getEntry(entry);
        if (e == null) {
            zip.close();
            throw new IllegalStateException("Mycelium: " + file.getFileName() + " has no entry '" + entry + "'");
        }
        return new java.io.FilterInputStream(zip.getInputStream(e)) {
            @Override
            public void close() throws IOException {
                try {
                    super.close();
                } finally {
                    zip.close();
                }
            }
        };
    }
}
