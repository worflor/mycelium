package dev.woflo.mycelium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Law P2, linkage (docs/eras.md): every class, method and field a jar reaches outside itself must exist in the era's
 * Java, whether or not a test happens to reach the call. The jar's outside references are read with ASM here; the era's
 * own JVM answers whether it has them ({@code LinkProbe}, written for Java 8). A test run only proves the paths it
 * takes; this proves them all.
 */
final class Linkage {
    /** JvmDowngrader replaces a call it has no backport for with {@code throw new MissingStubError(what)}. */
    static final String MISSING_STUB = "jvmdg/exc/MissingStubError";

    private Linkage() {
    }

    /** What a jar reaches outside itself: reference line (as LinkProbe reads it) → where it is first used. */
    static Map<String, String> outsideReferences(Path jar) throws IOException {
        Map<String, byte[]> classes = classes(jar);
        Set<String> inside = new HashSet<>(classes.keySet());
        Map<String, String> refs = new LinkedHashMap<>();
        Map<String, Hierarchy> hierarchy = new HashMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            Hierarchy h = new Hierarchy();
            new ClassReader(e.getValue()).accept(h, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            hierarchy.put(e.getKey(), h);
        }
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            new ClassReader(e.getValue()).accept(new Collector(e.getKey(), inside, refs, hierarchy),
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        return refs;
    }

    /** What a class of the jar extends and declares: enough to tell a member it declares from one it inherits. */
    private static final class Hierarchy extends ClassVisitor {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();

        Hierarchy() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            fields.add(name + " " + descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            methods.add(name + " " + descriptor);
            return null;
        }
    }

    /**
     * Calls the downgrader could not backport, traced to the artifact's own code. JvmDowngrader marks each gap with
     * {@code MissingStubError}: in the caller ({@code new MissingStubError(what)}), or inside a backport it only has in
     * part (the stub throws). Either way the first call would throw on the era's JVM.
     *
     * @param shadePrefix the package the backports were shaded under (they are not the artifact's own code)
     * @return one line per gap: {@code Caller#method -> what}
     */
    static List<String> missingStubs(Path jar, String shadePrefix) throws IOException {
        return missingStubs(jar, shadePrefix, true);
    }

    /**
     * {@link #missingStubs(Path, String)}, optionally without the backports that throw and that nothing in the artifact is
     * found to call: a backport is often partial in a corner the artifact never reaches (a process handle's
     * {@code onExit}), and a jar of the game's own size must not fail on every such corner.
     */
    static List<String> missingStubs(Path jar, String shadePrefix, boolean includeUnreachable) throws IOException {
        return missingStubs(jar, List.of(shadePrefix), includeUnreachable);
    }

    /** {@link #missingStubs(Path, String, boolean)} for a jar whose backports are shaded under more than one prefix. */
    static List<String> missingStubs(Path jar, List<String> shadePrefixes, boolean includeUnreachable) throws IOException {
        Map<String, byte[]> classes = classes(jar);
        List<String> backportDirs = shadePrefixes.stream().map(p -> p.replace('.', '/') + "/").collect(java.util.stream.Collectors.toList());
        Set<String> broken = new HashSet<>(); // owner#name of every method that throws MissingStubError
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            String owner = e.getKey();
            boolean own = backportDirs.stream().noneMatch(owner::startsWith);
            new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        String lastString = "";
                        boolean marked;

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String s) {
                                lastString = s;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            if (o.endsWith(MISSING_STUB) && !marked) {
                                marked = true;
                                broken.add(owner + "#" + name);
                                if (own) {
                                    out.add(owner + "#" + name + " -> " + (lastString.isEmpty() ? "an API with no backport" : lastString));
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            String owner = e.getKey();
            if (backportDirs.stream().anyMatch(owner::startsWith)) {
                continue;
            }
            new ClassReader(e.getValue()).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                            if (broken.contains(o + "#" + n)) {
                                String stub = o.substring(o.lastIndexOf('/') + 1);
                                String api = stub.substring(stub.lastIndexOf('_') + 1);
                                out.add(owner + "#" + name + " -> " + api + "." + n + " (backport " + stub + " is partial)");
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        if (out.isEmpty() && includeUnreachable) {
            broken.forEach(b -> out.add(b + " (a backport that throws; no caller in the artifact found)"));
        }
        return out;
    }

    /**
     * Asks the era's JVM about {@code refs}. The probe is compiled here, for Java 8, once per work directory.
     *
     * @return the number of unresolved references (the probe's report is in {@code log})
     */
    static int probe(Map<String, String> refs, Path work, String eraJava, Path log) throws IOException, InterruptedException {
        Path probeDir = work.resolve("link-probe");
        if (!Files.isRegularFile(probeDir.resolve("LinkProbe.class"))) {
            Files.createDirectories(probeDir);
            Path source = probeDir.resolve("LinkProbe.java");
            try (InputStream in = Linkage.class.getResourceAsStream("LinkProbe.java.txt")) {
                if (in == null) {
                    throw new IllegalStateException("Mycelium: LinkProbe source missing from the plugin");
                }
                Files.write(source, in.readAllBytes());
            }
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            int exit = javac.run(null, null, null, "--release", "8", "-nowarn", "-d", probeDir.toString(), source.toString());
            if (exit != 0) {
                throw new IllegalStateException("Mycelium: could not compile LinkProbe");
            }
        }
        Path refsFile = work.resolve("references.txt");
        StringBuilder sb = new StringBuilder();
        refs.forEach((ref, where) -> sb.append(ref).append('\t').append(where).append('\n'));
        Files.writeString(refsFile, sb.toString(), StandardCharsets.UTF_8);
        Process p = new ProcessBuilder(eraJava, "-cp", probeDir.toString(), "LinkProbe", refsFile.toString())
                .directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        return p.waitFor();
    }

    private static Map<String, byte[]> classes(Path jar) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.endsWith(".class") && !n.startsWith("META-INF/") && !n.endsWith("module-info.class")) {
                    out.put(n.substring(0, n.length() - ".class".length()), zip.readAllBytes());
                }
            }
        }
        return out;
    }

    /** Gathers every reference to a class outside the jar, and to its members. */
    private static final class Collector extends ClassVisitor {
        private final String owner;
        private final Set<String> inside;
        private final Map<String, String> refs;
        private final Map<String, Hierarchy> hierarchy;

        Collector(String owner, Set<String> inside, Map<String, String> refs, Map<String, Hierarchy> hierarchy) {
            super(Opcodes.ASM9);
            this.owner = owner;
            this.inside = inside;
            this.refs = refs;
            this.hierarchy = hierarchy;
        }

        void type(Type t, String where) {
            if (t.getSort() == Type.ARRAY) {
                t = t.getElementType();
            }
            if (t.getSort() == Type.OBJECT) {
                cls(t.getInternalName(), where);
            } else if (t.getSort() == Type.METHOD) {
                for (Type a : t.getArgumentTypes()) {
                    type(a, where);
                }
                type(t.getReturnType(), where);
            }
        }

        void cls(String internal, String where) {
            if (internal == null) {
                return;
            }
            if (internal.startsWith("[")) {
                type(Type.getType(internal), where);
            } else if (!inside.contains(internal)) {
                refs.putIfAbsent("C " + internal, where);
            }
        }

        void member(char kind, String o, String name, String desc, String where) {
            if (o.startsWith("[")) {
                type(Type.getType(o), where); // arrays: clone() and length exist on every one
            } else if (!inside.contains(o)) {
                cls(o, where);
                refs.putIfAbsent(kind + " " + o + " " + name + " " + desc, where);
            } else {
                inherited(kind, o, name, desc, where);
            }
            type(kind == 'M' ? Type.getMethodType(desc) : Type.getType(desc), where);
        }

        /**
         * A member named on a class of the jar that the jar does not declare is inherited: from a class of the jar, or
         * from the first class outside it that the superclass chain reaches, which is where the era's JVM must have it
         * (a class loader's {@code getDefinedPackage} named on the artifact's own loader exists only from Java 9). A
         * member an interface of the jar or outside it might supply is not traced.
         */
        void inherited(char kind, String o, String name, String desc, String where) {
            String key = name + " " + desc;
            String c = o;
            for (int hops = 0; hops < 64 && c != null; hops++) {
                Hierarchy h = hierarchy.get(c);
                if (h == null) {
                    refs.putIfAbsent(kind + " " + c + " " + name + " " + desc, where);
                    return;
                }
                if ((kind == 'M' ? h.methods : h.fields).contains(key)) {
                    return;
                }
                for (String i : h.interfaces) {
                    if (hierarchy.containsKey(i) && declaredAbove(kind, i, key, 0)) {
                        return;
                    }
                }
                c = h.superName;
            }
        }

        private boolean declaredAbove(char kind, String c, String key, int depth) {
            Hierarchy h = hierarchy.get(c);
            if (h == null || depth > 32) {
                return false;
            }
            if ((kind == 'M' ? h.methods : h.fields).contains(key)) {
                return true;
            }
            for (String i : h.interfaces) {
                if (declaredAbove(kind, i, key, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        void handle(Handle h, String where) {
            char kind = h.getTag() <= Opcodes.H_PUTSTATIC ? 'F' : 'M';
            member(kind, h.getOwner(), h.getName(), h.getDesc(), where);
        }

        void constant(Object value, String where) {
            if (value instanceof Type t) {
                type(t, where);
            } else if (value instanceof Handle h) {
                handle(h, where);
            }
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            cls(superName, owner);
            if (interfaces != null) {
                for (String i : interfaces) {
                    cls(i, owner);
                }
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            type(Type.getType(descriptor), owner + "." + name);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = owner + "#" + name;
            type(Type.getMethodType(descriptor), where);
            if (exceptions != null) {
                for (String e : exceptions) {
                    cls(e, where);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) {
                    cls(type, where);
                }

                @Override
                public void visitFieldInsn(int opcode, String o, String n, String d) {
                    member('F', o, n, d, where);
                }

                @Override
                public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) {
                    member('M', o, n, d, where);
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                    type(Type.getMethodType(d), where);
                    handle(bsm, where);
                    for (Object a : args) {
                        constant(a, where);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value, where);
                }

                @Override
                public void visitMultiANewArrayInsn(String d, int dims) {
                    type(Type.getType(d), where);
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    cls(type, where);
                }
            };
        }
    }
}
