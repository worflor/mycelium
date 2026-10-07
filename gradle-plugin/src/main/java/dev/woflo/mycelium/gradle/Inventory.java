package dev.woflo.mycelium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * The game's own class files, in the names an artifact runs on, held as a class inventory: every class with its superclass,
 * interfaces, fields and methods (constructors among them) so a reference is resolved the way the JVM resolves it, through
 * the hierarchy (docs/decisions/0010). It is what a {@code jar} entry reads (a game that ships in its runtime names, or an
 * API's jar) and, with Mojang's mappings applied to an obfuscated jar, a {@code proguard-jar} entry.
 *
 * <p>A member is {@link Names.Verdict#ABSENT} only when the whole hierarchy it would be found in is in the inventory and has
 * no such member. A hierarchy that leaves it (a game class extending a library's) is {@code UNKNOWN} at that edge, and the
 * Java platform's own classes, which every game class ends in, are read from the JVM that runs the build. Access, a
 * static member used as an instance one, and an interface called as a class are not held: the gate holds what the game has.
 */
final class Inventory implements Names {
    private static final class Cls {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> fields = new HashSet<>();
        final Set<String> methods = new HashSet<>();
    }

    private final String label;
    private final Map<String, Cls> classes = new HashMap<>();
    private final Map<String, Cls> jdk = new HashMap<>();

    private Inventory(String label) {
        this.label = label;
    }

    @Override
    public Verdict hasClass(String name) {
        return classes.containsKey(name) ? Verdict.PRESENT : Verdict.ABSENT;
    }

    @Override
    public Verdict hasMethod(List<String> owners, String name, String descriptor) {
        return resolve('M', owners, name + " " + descriptor);
    }

    @Override
    public Verdict hasField(List<String> owners, String name, String descriptor) {
        return resolve('F', owners, name + " " + descriptor);
    }

    @Override
    public String describe() {
        long methods = classes.values().stream().mapToLong(c -> c.methods.size()).sum();
        return label + ": " + classes.size() + " classes, " + methods + " methods";
    }

    private static final int FOUND = 0;
    private static final int MISSING = 1;
    private static final int UNCERTAIN = 2;

    private Verdict resolve(char kind, List<String> owners, String key) {
        boolean uncertain = false;
        for (String owner : owners) {
            switch (find(kind, owner, key, new HashSet<>(), 0)) {
                case FOUND:
                    return Verdict.PRESENT;
                case UNCERTAIN:
                    uncertain = true;
                    break;
                default:
                    break;
            }
        }
        return uncertain ? Verdict.UNKNOWN : Verdict.ABSENT;
    }

    private int find(char kind, String owner, String key, Set<String> seen, int depth) {
        if (depth > 64 || !seen.add(owner)) {
            return MISSING;
        }
        Cls c = classes.get(owner);
        if (c == null) {
            c = platform(owner);
        }
        if (c == null) {
            return depth == 0 ? MISSING : UNCERTAIN;
        }
        if ((kind == 'M' ? c.methods : c.fields).contains(key)) {
            return FOUND;
        }
        if (kind == 'M' && key.startsWith("<init> ")) {
            return MISSING; // a constructor is the class's own, never inherited
        }
        boolean uncertain = false;
        if (c.superName != null) {
            int r = find(kind, c.superName, key, seen, depth + 1);
            if (r == FOUND) {
                return FOUND;
            }
            uncertain |= r == UNCERTAIN;
        }
        for (String i : c.interfaces) {
            int r = find(kind, i, key, seen, depth + 1);
            if (r == FOUND) {
                return FOUND;
            }
            uncertain |= r == UNCERTAIN;
        }
        return uncertain ? UNCERTAIN : MISSING;
    }

    /** A class of the Java platform, read from the JVM running the build: the end of every game class's hierarchy. */
    private Cls platform(String name) {
        if (!name.startsWith("java/") && !name.startsWith("javax/")) {
            return null;
        }
        if (jdk.containsKey(name)) {
            return jdk.get(name);
        }
        Cls out = null;
        try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
            if (in != null) {
                out = read(in.readAllBytes(), null);
            }
        } catch (IOException e) {
            out = null;
        }
        jdk.put(name, out);
        return out;
    }

    private static Cls read(byte[] bytes, Remapper remapper) {
        Cls c = new Cls();
        ClassVisitor v = new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                c.superName = superName;
                c.interfaces = interfaces == null ? new String[0] : interfaces;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                c.fields.add(name + " " + descriptor);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                c.methods.add(name + " " + descriptor);
                return null;
            }
        };
        ClassReader reader = new ClassReader(bytes);
        int flags = ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;
        if (remapper == null) {
            reader.accept(v, flags);
        } else {
            reader.accept(new ClassRemapper(v, remapper), flags);
        }
        return c;
    }

    // --- reading -----------------------------------------------------------------------------------------------------------

    /**
     * A game that ships in the names an artifact runs on: {@code archive} is a jar, or a server's bundle of jars ({@code META-INF/versions.list}
     * and {@code libraries.list}), and every class under one of {@code owns} is inventoried.
     */
    static Inventory of(String label, Path archive, List<String> owns) throws IOException {
        Inventory out = new Inventory(label);
        out.load(archive, null, owns);
        return out.checked();
    }

    /** As {@link #of}, for an obfuscated game and Mojang's ProGuard-format mappings, which give every class its official name. */
    static Inventory of(String label, Path archive, InputStream proguard, List<String> owns) throws IOException {
        Inventory out = new Inventory(label);
        out.load(archive, ProGuard.parse(proguard), owns);
        return out.checked();
    }

    private Inventory checked() {
        if (classes.isEmpty()) {
            throw new IllegalStateException("Mycelium: " + label + " holds no class under the owned prefixes: it is not the game's archive");
        }
        return this;
    }

    private void load(Path archive, ProGuard map, List<String> owns) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            ZipEntry versions = zip.getEntry("META-INF/versions.list");
            if (versions == null) {
                readJar(zip, map, owns);
                return;
            }
            // a server bundle: the game's own jar, and the libraries it ships with (Brigadier, the data fixer, the profile library)
            for (String line : lines(zip, versions)) {
                String[] p = line.split("\t");
                if (p.length == 3) {
                    readNested(zip, "META-INF/versions/" + p[2], map, owns);
                }
            }
            ZipEntry libraries = zip.getEntry("META-INF/libraries.list");
            if (libraries != null) {
                for (String line : lines(zip, libraries)) {
                    String[] p = line.split("\t");
                    if (p.length == 3) {
                        readNested(zip, "META-INF/libraries/" + p[2], null, owns);
                    }
                }
            }
        }
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

    private void readJar(ZipFile zip, ProGuard map, List<String> owns) throws IOException {
        for (ZipEntry e : java.util.Collections.list(zip.entries())) {
            if (!e.isDirectory() && e.getName().endsWith(".class")) {
                try (InputStream in = zip.getInputStream(e)) {
                    add(in.readAllBytes(), map, owns);
                }
            }
        }
    }

    private void readNested(ZipFile outer, String entry, ProGuard map, List<String> owns) throws IOException {
        ZipEntry e = outer.getEntry(entry);
        if (e == null) {
            throw new IllegalStateException("Mycelium: " + label + ": the bundle lists " + entry + " and does not hold it");
        }
        try (ZipInputStream z = new ZipInputStream(new java.io.BufferedInputStream(outer.getInputStream(e)))) {
            for (ZipEntry n; (n = z.getNextEntry()) != null; ) {
                if (!n.isDirectory() && n.getName().endsWith(".class") && !n.getName().startsWith("META-INF/")) {
                    add(z.readAllBytes(), map, owns);
                }
            }
        }
    }

    private void add(byte[] bytes, ProGuard map, List<String> owns) {
        ClassReader reader = new ClassReader(bytes);
        String name = reader.getClassName();
        String named = map == null ? name : map.classes.getOrDefault(name, name);
        if (owns.stream().noneMatch(named::startsWith)) {
            return;
        }
        classes.put(named, read(bytes, map == null ? null : map.remapper()));
    }

    /**
     * Mojang's mappings in ProGuard's format ({@code official.Name -> obf:}, then each member indented, a method as
     * {@code 1:1:void name(java.lang.String) -> obf}): every obfuscated class, field and method to the official name.
     */
    static final class ProGuard {
        /** Obfuscated internal name to official internal name. */
        final Map<String, String> classes = new HashMap<>();
        private final Map<String, String> members = new HashMap<>();

        Remapper remapper() {
            return new Remapper() {
                @Override
                public String map(String internalName) {
                    return classes.getOrDefault(internalName, internalName);
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    return members.getOrDefault("M" + owner + "." + name + descriptor, name);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    return members.getOrDefault("F" + owner + "." + name + " " + descriptor, name);
                }
            };
        }

        static ProGuard parse(InputStream in) throws IOException {
            List<String[]> classRows = new ArrayList<>();
            List<String[]> memberRows = new ArrayList<>();
            Map<String, String> officialToObf = new HashMap<>();
            ProGuard out = new ProGuard();
            String owner = null;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.isBlank() || line.startsWith("#")) {
                        continue;
                    }
                    if (!line.startsWith(" ")) {
                        int arrow = line.indexOf(" -> ");
                        if (arrow < 0 || !line.endsWith(":")) {
                            throw new IllegalStateException("Mycelium: not a ProGuard mapping line: " + line);
                        }
                        String official = line.substring(0, arrow).replace('.', '/');
                        String obf = line.substring(arrow + 4, line.length() - 1).replace('.', '/');
                        out.classes.put(obf, official);
                        officialToObf.put(official, obf);
                        owner = obf;
                    } else {
                        if (owner == null) {
                            throw new IllegalStateException("Mycelium: a ProGuard member before any class: " + line);
                        }
                        int arrow = line.lastIndexOf(" -> ");
                        if (arrow < 0) {
                            throw new IllegalStateException("Mycelium: not a ProGuard member line: " + line);
                        }
                        memberRows.add(new String[] {owner, line.substring(0, arrow).strip(), line.substring(arrow + 4).strip()});
                    }
                }
            }
            for (String[] m : memberRows) {
                String declaration = m[1];
                // an optional leading line range, and for an inlined method a trailing one
                declaration = declaration.replaceFirst("^\\d+:\\d+:", "").replaceFirst(":\\d+:\\d+$", "");
                int open = declaration.indexOf('(');
                int space = declaration.indexOf(' ');
                if (space < 0) {
                    throw new IllegalStateException("Mycelium: not a ProGuard member: " + m[1]);
                }
                String type = declaration.substring(0, space);
                if (open < 0) {
                    out.members.put("F" + m[0] + "." + m[2] + " " + descriptor(type, officialToObf), declaration.substring(space + 1));
                } else {
                    String name = declaration.substring(space + 1, open);
                    String args = declaration.substring(open + 1, declaration.lastIndexOf(')'));
                    StringBuilder d = new StringBuilder("(");
                    if (!args.isBlank()) {
                        for (String a : args.split(",")) {
                            d.append(descriptor(a.strip(), officialToObf));
                        }
                    }
                    d.append(')').append(descriptor(type, officialToObf));
                    out.members.put("M" + m[0] + "." + m[2] + d, name);
                }
            }
            if (out.classes.isEmpty()) {
                throw new IllegalStateException("Mycelium: the mappings hold no class: they are not ProGuard's format");
            }
            return out;
        }

        /** A Java source type ({@code int[]}, {@code net.minecraft.Foo}) as an obfuscated descriptor. */
        static String descriptor(String type, Map<String, String> officialToObf) {
            int dims = 0;
            String t = type;
            while (t.endsWith("[]")) {
                dims++;
                t = t.substring(0, t.length() - 2);
            }
            String base = switch (t) {
                case "void" -> "V";
                case "boolean" -> "Z";
                case "byte" -> "B";
                case "char" -> "C";
                case "short" -> "S";
                case "int" -> "I";
                case "long" -> "J";
                case "float" -> "F";
                case "double" -> "D";
                default -> {
                    String internal = t.replace('.', '/');
                    yield "L" + officialToObf.getOrDefault(internal, internal) + ";";
                }
            };
            return "[".repeat(dims) + base;
        }
    }

    /** For tests: an inventory over class files given by internal name. */
    static Inventory over(String label, Map<String, byte[]> classFiles) {
        Inventory out = new Inventory(label);
        classFiles.forEach((name, bytes) -> out.classes.put(name, read(bytes, null)));
        return out;
    }

    /** For tests: the same, in an obfuscated game that {@code proguard} names. */
    static Inventory over(String label, Map<String, byte[]> obfuscated, String proguard, List<String> owns) throws IOException {
        Inventory out = new Inventory(label);
        ProGuard map = ProGuard.parse(new ByteArrayInputStream(proguard.getBytes(StandardCharsets.UTF_8)));
        for (byte[] bytes : obfuscated.values()) {
            out.add(bytes, map, owns);
        }
        return out.checked();
    }
}
