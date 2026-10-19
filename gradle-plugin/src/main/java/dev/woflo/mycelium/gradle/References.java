package dev.woflo.mycelium.gradle;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.zip.ZipFile;

/**
 * Every class, method and field an artifact's classes reach that the artifact does not itself define, read from the class
 * files (docs/decisions/0010): the constant pool's references, and also the types its own signatures, annotations and
 * handles name. A member named on one of the artifact's own classes that the artifact does not declare is inherited, and
 * is named on the first class outside the artifact its superclasses reach (then the outside interfaces on the way), which
 * is where the game would have to have it.
 *
 * <p>It reads bytecode, so it sees what a link resolves. A class named only in a string (a reflective load), a Mixin target
 * written as text, and a generic signature are not references to it, and are not seen.
 */
final class References {
    /** One reference: a class ({@code C}), a method ({@code M}) or a field ({@code F}), on {@code owners} (the first, then its fallbacks). */
    record Ref(char kind, List<String> owners, String name, String descriptor) {
        String owner() {
            return owners.get(0);
        }

        /** The reference as a person reads it: {@code net/minecraft/a/B.name(desc)} for a member, the class name for a class. */
        String text() {
            return switch (kind) {
                case 'C' -> "class " + owner();
                case 'M' -> "method " + owner() + "." + name + descriptor;
                default -> "field " + owner() + "." + name + ":" + descriptor;
            };
        }
    }

    /** The references found, each with where it is first made, and how many class files were read. */
    record Found(Map<Ref, String> refs, int classFiles, int excluded) {
    }

    private References() {
    }

    /**
     * The references of every class of {@code archive}, and of the jars inside it that are named in {@code nested}, but for the
     * classes {@code excluded} (internal names): those are the artifact's own, and not held.
     */
    static Found scan(Path archive, List<String> nested, Set<String> excluded) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                if (e.isDirectory()) {
                    continue;
                }
                if (nested.contains(e.getName())) {
                    try (InputStream in = zip.getInputStream(e)) {
                        readNested(in.readAllBytes(), classes);
                    }
                } else if (isClass(e.getName())) {
                    try (InputStream in = zip.getInputStream(e)) {
                        add(classes, e.getName(), in.readAllBytes());
                    }
                }
            }
        }
        return scan(classes, excluded);
    }

    private static void readNested(byte[] jar, Map<String, byte[]> into) throws IOException {
        Path temp = Files.createTempFile("mycelium-nested-", ".jar");
        try {
            Files.write(temp, jar);
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                    if (!e.isDirectory() && isClass(e.getName())) {
                        try (InputStream in = zip.getInputStream(e)) {
                            add(into, e.getName(), in.readAllBytes());
                        }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static boolean isClass(String entry) {
        return entry.endsWith(".class") && !entry.startsWith("META-INF/") && !entry.endsWith("module-info.class");
    }

    private static void add(Map<String, byte[]> into, String entry, byte[] bytes) {
        into.put(entry.substring(0, entry.length() - ".class".length()), bytes);
    }

    /** The references of {@code classes} (internal name to class file bytes). */
    static Found scan(Map<String, byte[]> classes, Set<String> excluded) {
        Map<String, Declared> own = new HashMap<>();
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            Declared d = new Declared();
            new ClassReader(e.getValue()).accept(d, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            own.put(e.getKey(), d);
        }
        Map<Ref, String> refs = new LinkedHashMap<>();
        int skipped = 0;
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            if (excluded.contains(e.getKey())) {
                skipped++; // still the artifact's own, so a member named on it resolves through it; only its references are not held
                continue;
            }
            // Keep debug metadata on the reference pass: instruction references can then carry their source line.
            new ClassReader(e.getValue()).accept(new Collector(e.getKey(), own, refs), ClassReader.SKIP_FRAMES);
        }
        return new Found(refs, classes.size() - skipped, skipped);
    }

    /** What a class of the artifact extends and declares: enough to tell a member it declares from one it inherits. */
    private static final class Declared extends ClassVisitor {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();

        Declared() {
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

    private static final class Collector extends ClassVisitor {
        private final String caller;
        private final Map<String, Declared> own;
        private final Map<Ref, String> refs;
        private String sourceFile;

        Collector(String caller, Map<String, Declared> own, Map<Ref, String> refs) {
            super(Opcodes.ASM9);
            this.caller = caller;
            this.own = own;
            this.refs = refs;
        }

        void type(Type t, String where) {
            switch (t.getSort()) {
                case Type.ARRAY -> type(t.getElementType(), where);
                case Type.OBJECT -> cls(t.getInternalName(), where);
                case Type.METHOD -> {
                    for (Type a : t.getArgumentTypes()) {
                        type(a, where);
                    }
                    type(t.getReturnType(), where);
                }
                default -> {
                }
            }
        }

        void cls(String internal, String where) {
            if (internal == null) {
                return;
            }
            if (internal.startsWith("[")) {
                type(Type.getType(internal), where);
            } else if (!own.containsKey(internal)) {
                refs.putIfAbsent(new Ref('C', List.of(internal), "", ""), where);
            }
        }

        void member(char kind, String owner, String name, String descriptor, String where) {
            if (owner.startsWith("[")) {
                type(Type.getType(owner), where);
            } else if (!own.containsKey(owner)) {
                cls(owner, where);
                refs.putIfAbsent(new Ref(kind, List.of(owner), name, descriptor), where);
            } else {
                inherited(kind, owner, name, descriptor, where);
            }
            type(kind == 'M' ? Type.getMethodType(descriptor) : Type.getType(descriptor), where);
        }

        /**
         * A member named on a class of the artifact: found there or above it in the artifact (not a reference outside),
         * else named on the outside classes its hierarchy reaches, the superclass first.
         */
        void inherited(char kind, String owner, String name, String descriptor, String where) {
            String key = name + " " + descriptor;
            List<String> outside = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (declaredAbove(kind, owner, key, outside, seen, 0)) {
                return;
            }
            if (!outside.isEmpty()) {
                refs.putIfAbsent(new Ref(kind, List.copyOf(outside), name, descriptor), where);
            }
        }

        private boolean declaredAbove(char kind, String c, String key, List<String> outside, Set<String> seen, int depth) {
            if (depth > 64 || !seen.add(c)) {
                return false;
            }
            Declared d = own.get(c);
            if (d == null) {
                outside.add(c);
                return false;
            }
            if ((kind == 'M' ? d.methods : d.fields).contains(key)) {
                return true;
            }
            if (d.superName != null && declaredAbove(kind, d.superName, key, outside, seen, depth + 1)) {
                return true;
            }
            for (String i : d.interfaces) {
                if (declaredAbove(kind, i, key, outside, seen, depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        void handle(Handle h, String where) {
            member(h.getTag() <= Opcodes.H_PUTSTATIC ? 'F' : 'M', h.getOwner(), h.getName(), h.getDesc(), where);
        }

        void constant(Object value, String where) {
            if (value instanceof Type t) {
                type(t, where);
            } else if (value instanceof Handle h) {
                handle(h, where);
            } else if (value instanceof ConstantDynamic c) {
                type(Type.getType(c.getDescriptor()), where);
                handle(c.getBootstrapMethod(), where);
                for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) {
                    constant(c.getBootstrapMethodArgument(i), where);
                }
            }
        }

        AnnotationVisitor annotation(String descriptor, String where) {
            if (descriptor != null) {
                type(Type.getType(descriptor), where);
            }
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    constant(value, where);
                }

                @Override
                public void visitEnum(String name, String enumDescriptor, String value) {
                    type(Type.getType(enumDescriptor), where);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String nestedDescriptor) {
                    type(Type.getType(nestedDescriptor), where);
                    return this;
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return this;
                }
            };
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            cls(superName, caller);
            if (interfaces != null) {
                for (String i : interfaces) {
                    cls(i, caller);
                }
            }
        }

        @Override
        public void visitSource(String source, String debug) {
            if (source != null) {
                int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
                sourceFile = source.substring(slash + 1);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor, caller);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
            return annotation(descriptor, caller);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            type(Type.getType(descriptor), caller + "." + name);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            String where = caller + "." + name;
            type(Type.getType(descriptor), where);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotation(annotationDescriptor, where);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String annotationDescriptor, boolean visible) {
                    return annotation(annotationDescriptor, where);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = caller + "#" + name;
            type(Type.getMethodType(descriptor), where);
            if (exceptions != null) {
                for (String e : exceptions) {
                    cls(e, where);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                private int line = -1;

                private String instructionWhere() {
                    return sourceFile == null || line < 1 ? where : sourceFile + ":" + line + " (" + where + ")";
                }

                @Override
                public void visitLineNumber(int line, Label start) {
                    this.line = line;
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    cls(type, instructionWhere());
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String n, String d) {
                    member('F', owner, n, d, instructionWhere());
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String n, String d, boolean itf) {
                    member('M', owner, n, d, instructionWhere());
                }

                @Override
                public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                    String at = instructionWhere();
                    type(Type.getMethodType(d), at);
                    handle(bsm, at);
                    for (Object a : args) {
                        constant(a, at);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    constant(value, instructionWhere());
                }

                @Override
                public void visitMultiANewArrayInsn(String d, int dims) {
                    type(Type.getType(d), instructionWhere());
                }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    cls(type, where);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String annotationDescriptor, boolean visible) {
                    return annotation(annotationDescriptor, where);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String annotationDescriptor, boolean visible) {
                    return annotation(annotationDescriptor, where);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(int parameter, String annotationDescriptor, boolean visible) {
                    return annotation(annotationDescriptor, where);
                }

                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotation(null, where);
                }
            };
        }
    }
}
