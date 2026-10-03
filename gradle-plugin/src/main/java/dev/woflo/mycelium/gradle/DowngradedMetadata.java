package dev.woflo.mycelium.gradle;

import org.gradle.api.GradleException;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/** Carries JvmDowngrader types referenced by a downgraded artifact into its private namespace. */
final class DowngradedMetadata {
    private static final String API_ROOT = "xyz/wagyourtail/jvmdg/";

    private DowngradedMetadata() { }

    static Result relocate(Path artifact, Path downgradedApi, Path pinnedTool, String prefix, int targetJava) throws IOException {
        String privateRoot = prefix.replace('.', '/') + "/";
        Map<String, byte[]> classes = readClasses(artifact);
        Map<String, byte[]> api = readClasses(downgradedApi);
        Map<String, byte[]> tool = readClasses(pinnedTool);
        Set<String> needed = new LinkedHashSet<>();
        for (byte[] bytes : classes.values()) {
            needed.addAll(jvmdgReferences(List.of(unmapPrivate(bytes, privateRoot))));
        }
        if (needed.isEmpty()) {
            return new Result(0, List.of(), List.of(), List.of(), List.of(), 0);
        }

        Map<String, byte[]> closure = closure(needed, api, tool, classes, privateRoot);
        int maxMajor = 44 + targetJava;
        Map<String, byte[]> relocated = new TreeMap<>();
        List<String> fromApi = new ArrayList<>();
        List<String> fromTool = new ArrayList<>();
        List<String> fromArtifact = new ArrayList<>();
        int maxToolMajor = 0;
        for (Map.Entry<String, byte[]> item : closure.entrySet()) {
            String name = item.getKey();
            byte[] bytes = item.getValue();
            int major = major(bytes);
            if (major > maxMajor) {
                throw new GradleException("Mycelium: JvmDowngrader metadata dependency " + name + " has class version " + major
                        + ", more than Java " + targetJava + " runs");
            }
            if (classes.containsKey(privateRoot + name + ".class")) fromArtifact.add(name);
            else if (api.containsKey(name + ".class")) fromApi.add(name);
            else {
                fromTool.add(name);
                maxToolMajor = Math.max(maxToolMajor, major);
            }
            relocated.put(privateRoot + name + ".class", remap(bytes, privateRoot));
        }

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String name = entry.getKey();
            if (!name.endsWith(".class")) continue;
            byte[] rewritten = remap(entry.getValue(), privateRoot);
            if (!java.util.Arrays.equals(rewritten, entry.getValue())) {
                classes.put(name, rewritten);
            }
        }

        Path temp = artifact.resolveSibling(artifact.getFileName() + ".metadata.tmp");
        Set<String> seen = new HashSet<>();
        try (JarFile source = new JarFile(artifact.toFile()); JarOutputStream output = new JarOutputStream(Files.newOutputStream(temp))) {
            Enumeration<JarEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!seen.add(name)) {
                    throw new GradleException("Mycelium: duplicate jar entry while relocating downgraded metadata: " + name);
                }
                JarEntry copy = new JarEntry(name);
                if (entry.getTime() >= 0) copy.setTime(entry.getTime());
                output.putNextEntry(copy);
                byte[] bytes;
                if (classes.containsKey(name)) {
                    bytes = classes.get(name);
                } else {
                    try (InputStream in = source.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                }
                output.write(bytes);
                output.closeEntry();
            }
            for (Map.Entry<String, byte[]> entry : relocated.entrySet()) {
                byte[] existing = classes.get(entry.getKey());
                if (existing != null && !java.util.Arrays.equals(existing, entry.getValue())) {
                    throw new GradleException("Mycelium: relocated JvmDowngrader metadata class collides with artifact class "
                            + entry.getKey());
                }
                if (existing == null) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        Files.move(temp, artifact, StandardCopyOption.REPLACE_EXISTING);
        return new Result(closure.size(), List.copyOf(closure.keySet()), List.copyOf(fromArtifact), List.copyOf(fromApi),
                List.copyOf(fromTool), maxToolMajor);
    }

    private static Map<String, byte[]> readClasses(Path jar) throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes;
                try (InputStream in = file.getInputStream(entry)) { bytes = in.readAllBytes(); }
                String expected = entry.getName();
                if (expected.startsWith("META-INF/versions/")) {
                    int versionEnd = expected.indexOf('/', "META-INF/versions/".length());
                    if (versionEnd < 0) {
                        throw new GradleException("Mycelium: malformed multi-release class entry " + expected);
                    }
                    expected = expected.substring(versionEnd + 1);
                }
                String identity = new ClassReader(bytes).getClassName() + ".class";
                if (!expected.equals(identity)) {
                    throw new GradleException("Mycelium: class entry " + entry.getName() + " declares " + identity);
                }
                if (classes.putIfAbsent(entry.getName(), bytes) != null) {
                    throw new GradleException("Mycelium: duplicate class entry " + entry.getName());
                }
            }
        }
        return classes;
    }

    private static Set<String> jvmdgReferences(Iterable<byte[]> classes) {
        Set<String> refs = new LinkedHashSet<>();
        for (byte[] bytes : classes) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                    addInternal(superName, refs);
                    if (interfaces != null) for (String face : interfaces) addInternal(face, refs);
                }
                @Override public void visitOuterClass(String owner, String name, String descriptor) {
                    addInternal(owner, refs);
                    addDescriptor(descriptor, refs);
                }
                @Override public void visitNestHost(String nestHost) { addInternal(nestHost, refs); }
                @Override public void visitNestMember(String nestMember) { addInternal(nestMember, refs); }
                @Override public void visitPermittedSubclass(String permittedSubclass) { addInternal(permittedSubclass, refs); }
                @Override public void visitInnerClass(String name, String outerName, String innerName, int access) {
                    addInternal(name, refs);
                    addInternal(outerName, refs);
                }
                @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(descriptor, refs);
                }
                @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,
                        org.objectweb.asm.TypePath typePath, String descriptor, boolean visible) {
                    return annotation(descriptor, refs);
                }
                @Override public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                    addDescriptor(descriptor, refs);
                    return new RecordComponentVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                            return annotation(desc, refs);
                        }
                        @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String desc, boolean visible) {
                            return annotation(desc, refs);
                        }
                    };
                }
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    addDescriptor(descriptor, refs);
                    addConstant(value, refs);
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) { return annotation(desc, refs); }
                        @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String desc, boolean visible) { return annotation(desc, refs); }
                    };
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    addDescriptor(descriptor, refs);
                    if (exceptions != null) for (String exception : exceptions) addInternal(exception, refs);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public AnnotationVisitor visitAnnotation(String desc, boolean visible) { return annotation(desc, refs); }
                        @Override public AnnotationVisitor visitTypeAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String desc, boolean visible) { return annotation(desc, refs); }
                        @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
                            return annotation(desc, refs);
                        }
                        @Override public AnnotationVisitor visitAnnotationDefault() { return closureAnnotationValue(refs); }
                        @Override public AnnotationVisitor visitInsnAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String desc, boolean visible) { return annotation(desc, refs); }
                        @Override public AnnotationVisitor visitTryCatchAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, String desc, boolean visible) { return annotation(desc, refs); }
                        @Override public AnnotationVisitor visitLocalVariableAnnotation(int typeRef,
                                org.objectweb.asm.TypePath typePath, org.objectweb.asm.Label[] start,
                                org.objectweb.asm.Label[] end, int[] index, String desc, boolean visible) {
                            return annotation(desc, refs);
                        }
                        @Override public void visitTypeInsn(int opcode, String type) { addInsnType(type, refs); }
                        @Override public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            addInternal(owner, refs);
                            addDescriptor(desc, refs);
                        }
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
                            addInternal(owner, refs);
                            addDescriptor(desc, refs);
                        }
                        @Override public void visitInvokeDynamicInsn(String name, String desc,
                                org.objectweb.asm.Handle bootstrap, Object... arguments) {
                            addDescriptor(desc, refs);
                            addHandle(bootstrap, refs);
                            for (Object argument : arguments) addConstant(argument, refs);
                        }
                        @Override public void visitLdcInsn(Object value) { addConstant(value, refs); }
                        @Override public void visitMultiANewArrayInsn(String desc, int dimensions) { addDescriptor(desc, refs); }
                        @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                org.objectweb.asm.Label handler, String type) { addInternal(type, refs); }
                        @Override public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
                            for (int i = 0; i < numLocal; i++) if (local[i] instanceof String name) addInsnType(name, refs);
                            for (int i = 0; i < numStack; i++) if (stack[i] instanceof String name) addInsnType(name, refs);
                        }
                    };
                }
            }, 0);
        }
        return refs;
    }

    private static AnnotationVisitor annotation(String descriptor, Set<String> refs) {
        addDescriptor(descriptor, refs);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) {
                if (value instanceof Type type) addType(type, refs);
            }
            @Override public void visitEnum(String name, String descriptor, String value) { addDescriptor(descriptor, refs); }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { return annotation(descriptor, refs); }
            @Override public AnnotationVisitor visitArray(String name) { return this; }
        };
    }

    private static Map<String, byte[]> closure(Set<String> start, Map<String, byte[]> api, Map<String, byte[]> tool,
            Map<String, byte[]> artifact, String privateRoot) {
        Set<String> found = new LinkedHashSet<>();
        Map<String, byte[]> selected = new TreeMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>(start);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!found.add(name)) continue;
            byte[] bytes = artifact.get(privateRoot + name + ".class");
            if (bytes == null) bytes = api.get(name + ".class");
            if (bytes == null) bytes = tool.get(name + ".class");
            if (bytes == null) {
                throw new GradleException("Mycelium: required JvmDowngrader metadata class is missing from the target API and pinned tool inputs: "
                        + name);
            }
            selected.put(name, bytes);
            byte[] referenceClass = artifact.containsKey(privateRoot + name + ".class") ? unmapPrivate(bytes, privateRoot) : bytes;
            pending.addAll(jvmdgReferences(List.of(referenceClass)));
        }
        return selected;
    }

    private static byte[] unmapPrivate(byte[] bytes, String privateRoot) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        String relocatedApiRoot = privateRoot + API_ROOT;
        Remapper remapper = new Remapper() {
            @Override public String map(String name) {
                return name.startsWith(relocatedApiRoot) ? name.substring(privateRoot.length()) : name;
            }
        };
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static AnnotationVisitor closureAnnotation(String descriptor, Collection<String> pending) {
        addDescriptor(descriptor, pending);
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { if (value instanceof Type type) addType(type, pending); }
            @Override public void visitEnum(String name, String descriptor, String value) { addDescriptor(descriptor, pending); }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { return closureAnnotation(descriptor, pending); }
            @Override public AnnotationVisitor visitArray(String name) { return this; }
        };
    }

    private static AnnotationVisitor closureAnnotationValue(Collection<String> pending) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override public void visit(String name, Object value) { if (value instanceof Type type) addType(type, pending); }
            @Override public void visitEnum(String name, String descriptor, String value) { addDescriptor(descriptor, pending); }
            @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { return closureAnnotation(descriptor, pending); }
            @Override public AnnotationVisitor visitArray(String name) { return this; }
        };
    }

    private static void addConstant(Object value, Collection<String> refs) {
        if (value instanceof Type type) addType(type, refs);
        else if (value instanceof org.objectweb.asm.Handle handle) addHandle(handle, refs);
        else if (value instanceof ConstantDynamic dynamic) {
            addDescriptor(dynamic.getDescriptor(), refs);
            addHandle(dynamic.getBootstrapMethod(), refs);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                addConstant(dynamic.getBootstrapMethodArgument(i), refs);
            }
        }
    }

    private static void addHandle(org.objectweb.asm.Handle handle, Collection<String> refs) {
        addInternal(handle.getOwner(), refs);
        addDescriptor(handle.getDesc(), refs);
    }

    private static void addDescriptor(String descriptor, Collection<String> refs) {
        if (descriptor == null) return;
        Type type = Type.getType(descriptor);
        addType(type, refs);
    }

    private static void addType(Type type, Collection<String> refs) {
        if (type.getSort() == Type.ARRAY) addType(type.getElementType(), refs);
        else if (type.getSort() == Type.OBJECT) addInternal(type.getInternalName(), refs);
        else if (type.getSort() == Type.METHOD) {
            addType(type.getReturnType(), refs);
            for (Type arg : type.getArgumentTypes()) addType(arg, refs);
        }
    }

    private static void addInternal(String name, Collection<String> refs) {
        if (name != null && name.startsWith(API_ROOT)) refs.add(name);
    }

    private static void addInsnType(String name, Collection<String> refs) {
        if (name.startsWith("[")) addDescriptor(name, refs);
        else addInternal(name, refs);
    }

    private static int major(byte[] bytes) {
        return ((bytes[6] & 0xff) << 8) | (bytes[7] & 0xff);
    }

    private static byte[] remap(byte[] bytes, String privateRoot) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(0);
        Remapper remapper = new Remapper() {
            @Override public String map(String name) {
                return name.startsWith(API_ROOT) ? privateRoot + name : name;
            }
        };
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    record Result(int classes, List<String> names, List<String> artifactClasses, List<String> apiClasses,
            List<String> toolClasses, int toolClassMajorMaximum) { }
}
