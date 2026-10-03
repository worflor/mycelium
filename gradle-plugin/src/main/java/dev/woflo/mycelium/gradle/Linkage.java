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
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.DigestInputStream;
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
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;

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

    /** A verified nested archive and runtime linkage summary. */
    static record CapsuleResult(int classFiles, int classReferences, int memberReferences,
            Map<String, Integer> classMajors, List<String> runtimeRootHashes, List<String> shadowedRuntimeClasses,
            String archiveHash, String mappingsHash) {
    }

    /** Checks bytecode ceilings, final mapped names, and linkage against the declared runtime roots. */
    static CapsuleResult verifyCapsule(Path archive, int outerMaxMajor, Map<String, Integer> nestedMaxMajor,
            List<Path> runtimeRoots, Path tinyMappings, String fromNamespace, String toNamespace,
            List<String> mappedPrefixes) throws IOException {
        require(!runtimeRoots.isEmpty(), "myceliumCapsule has no declared runtimeArtifacts; the gate cannot prove linkage");
        Map<String, RuntimeClass> runtime = new LinkedHashMap<>();
        List<String> rootHashes = new ArrayList<>();
        List<String> shadowedRuntimeClasses = new ArrayList<>();
        Map<String, String> runtimeOrigins = new HashMap<>();
        for (Path root : runtimeRoots) {
            String rootHash = sha256(root);
            rootHashes.add(root.getFileName() + "=" + rootHash);
            try (ZipFile zip = new ZipFile(root.toFile())) {
                readRuntimeClasses(zip, runtime, runtimeOrigins, shadowedRuntimeClasses,
                        root.getFileName() + " sha256=" + rootHash);
            }
        }
        Map<String, String> mappings = tinyClassMappings(tinyMappings, fromNamespace, toNamespace);
        Map<String, byte[]> artifactClasses = new LinkedHashMap<>();
        Map<String, String> artifactGroups = new HashMap<>();
        Map<String, Integer> majors = new TreeMap<>();
        Map<String, byte[]> nestedArchives = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            Set<String> seenEntries = new HashSet<>();
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                require(seenEntries.add(entry.getName()), "capsule has duplicate archive entry: " + entry.getName());
                if (entry.isDirectory()) continue;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
                if (!entry.isDirectory() && isZip(bytes)) {
                    require(nestedMaxMajor.containsKey(entry.getName()),
                            "capsule contains an undeclared nested archive: " + entry.getName());
                }
                if (entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(bytes);
                    require(reader.getClassName().equals(entry.getName().substring(0, entry.getName().length() - 6)),
                            "class entry name does not match its internal name: " + entry.getName() + " declares " + reader.getClassName());
                    int major = reader.readUnsignedShort(6);
                    require(major <= outerMaxMajor, entry.getName() + " has class major " + major + "; outer ceiling is " + outerMaxMajor);
                    addArtifactClass(artifactClasses, artifactGroups, reader.getClassName(), bytes, "anchor");
                    majors.put(entry.getName(), major);
                } else if (nestedMaxMajor.containsKey(entry.getName())) {
                    nestedArchives.put(entry.getName(), bytes);
                }
            }
        }
        require(!artifactClasses.isEmpty(), "capsule has no outer class files");
        for (Map.Entry<String, Integer> nested : nestedMaxMajor.entrySet()) {
            byte[] bytes = nestedArchives.get(nested.getKey());
            require(bytes != null, "capsule has no nested archive at " + nested.getKey());
            Map<String, byte[]> contents = readNestedClasses(bytes, nested.getKey(), nested.getValue(), majors);
            require(!contents.isEmpty(), "nested archive " + nested.getKey() + " has no class files");
            for (Map.Entry<String, byte[]> c : contents.entrySet()) {
                addArtifactClass(artifactClasses, artifactGroups, c.getKey(), c.getValue(), "nested");
            }
        }
        for (Map.Entry<String, byte[]> c : artifactClasses.entrySet()) {
            RuntimeClass runtimeDefinition = runtime.get(c.getKey());
            require(runtimeDefinition == null || runtimeDefinition.digest.equals(sha256(c.getValue())),
                    "capsule class collides with a different runtime definition: " + c.getKey());
            if (runtimeDefinition != null) artifactGroups.put(c.getKey(), "anchor");
        }
        RuntimeInventory inventory = new RuntimeInventory(runtime, artifactClasses, artifactGroups);
        List<String> failures = new ArrayList<>();
        int classReferences = 0;
        int memberReferences = 0;
        for (Map.Entry<String, byte[]> c : artifactClasses.entrySet()) {
            Scan scan = new Scan(c.getKey(), inventory, mappings, mappedPrefixes, failures);
            new ClassReader(c.getValue()).accept(scan, ClassReader.SKIP_DEBUG);
            classReferences += scan.classReferences;
            memberReferences += scan.memberReferences;
        }
        require(failures.isEmpty(), "capsule linkage failed:\n  " + String.join("\n  ", failures.stream().limit(40).toList()));
        return new CapsuleResult(artifactClasses.size(), classReferences, memberReferences, majors, rootHashes, shadowedRuntimeClasses,
                sha256(archive), tinyMappings == null ? "" : sha256(tinyMappings));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException("Mycelium: " + message);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isZip(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4) || (bytes[2] == 5 && bytes[3] == 6) || (bytes[2] == 7 && bytes[3] == 8));
    }

    private static String packageName(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? "" : name.substring(0, slash);
    }

    private static void addArtifactClass(Map<String, byte[]> classes, Map<String, String> groups,
            String name, byte[] bytes, String group) {
        require(!classes.containsKey(name), "capsule defines " + name + " in more than one archive or entry");
        classes.put(name, bytes);
        groups.put(name, group);
    }

    private static Map<String, String> tinyClassMappings(Path file, String from, String to) throws IOException {
        if (file == null) return Map.of();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        require(!lines.isEmpty(), "Tiny mapping file is empty: " + file);
        String[] header = lines.get(0).split("\t", -1);
        require(header.length >= 5 && header[0].equals("tiny") && header[1].equals("2"), "capsule mappings must use Tiny v2: " + file);
        List<String> namespaces = List.of(header).subList(3, header.length);
        int a = namespaces.indexOf(from), b = namespaces.indexOf(to);
        require(a >= 0 && b >= 0 && a != b, "Tiny mappings do not declare namespaces " + from + " and " + to + ": " + file);
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.startsWith("c\t")) continue;
            String[] columns = line.split("\t", -1);
            require(columns.length == namespaces.size() + 1, "malformed Tiny class row: " + line);
            String source = columns[a + 1], target = columns[b + 1];
            if (!source.isEmpty() && !target.isEmpty()) {
                String old = out.putIfAbsent(source, target);
                require(old == null || old.equals(target), "conflicting Tiny mapping for class " + source);
            }
        }
        return out;
    }

    private static void readRuntimeClasses(ZipFile zip, Map<String, RuntimeClass> into, Map<String, String> origins,
            List<String> shadowed, String source) throws IOException {
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            String name = entry.getName();
            if (entry.isDirectory() || !name.endsWith(".class")) continue;
            String className = name.substring(0, name.length() - 6);
            RuntimeClass c;
            try (InputStream in = zip.getInputStream(entry)) { c = RuntimeClass.read(in.readAllBytes()); }
            require(c.name.equals(className), "runtime class entry name does not match its internal name in " + source + ": "
                    + name + " declares " + c.name);
            RuntimeClass old = into.putIfAbsent(className, c);
            if (old == null) origins.put(className, source + " sha256=" + c.digest);
            else if (!old.digest.equals(c.digest)) {
                shadowed.add(className + ": " + source + " sha256=" + c.digest + " is shadowed by " + origins.get(className));
            }
        }
    }

    private static Map<String, byte[]> readNestedClasses(byte[] archive, String name, int ceiling,
            Map<String, Integer> majors) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Path temp = Files.createTempFile("mycelium-capsule-", ".jar");
        try {
            Files.write(temp, archive);
            try (ZipFile zip = new ZipFile(temp.toFile())) {
                Set<String> seenEntries = new HashSet<>();
                for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                    require(seenEntries.add(e.getName()), "nested archive " + name + " has duplicate entry: " + e.getName());
                    if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                    byte[] bytes;
                    try (InputStream in = zip.getInputStream(e)) { bytes = in.readAllBytes(); }
                    ClassReader reader = new ClassReader(bytes);
                    int major = reader.readUnsignedShort(6);
                    require(major <= ceiling, "nested " + name + "!" + e.getName() + " has class major " + major + "; nested ceiling is " + ceiling);
                    require(reader.getClassName().equals(e.getName().substring(0, e.getName().length() - 6)),
                            "nested class entry name does not match its internal name: " + name + "!" + e.getName()
                                    + " declares " + reader.getClassName());
                    require(out.putIfAbsent(reader.getClassName(), bytes) == null,
                            "nested archive " + name + " defines " + reader.getClassName() + " more than once");
                    majors.put(name + "!" + e.getName(), major);
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return out;
    }

    private static final class RuntimeClass {
        final String name;
        final String parent;
        final List<String> interfaces;
        final int access;
        final boolean isInterface;
        final int major;
        String nestHost;
        final Set<String> nestMembers = new HashSet<>();
        final Map<String, Member> fields = new LinkedHashMap<>();
        final Map<String, Member> methods = new LinkedHashMap<>();
        final String digest;

        RuntimeClass(String name, String parent, List<String> interfaces, int access, int major, String digest) {
            this.name = name; this.parent = parent; this.interfaces = interfaces; this.access = access;
            this.isInterface = (access & Opcodes.ACC_INTERFACE) != 0; this.major = major; this.digest = digest;
        }

        static RuntimeClass read(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            RuntimeClass[] result = new RuntimeClass[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                    result[0] = new RuntimeClass(name, parent, interfaces == null ? List.of() : List.of(interfaces),
                            access, reader.readUnsignedShort(6), sha256(bytes));
                }
                @Override public void visitNestHost(String host) { result[0].nestHost = host; }
                @Override public void visitNestMember(String member) { result[0].nestMembers.add(member); }
                @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
                    result[0].fields.put(name + " " + desc, new Member(access, name, desc)); return null;
                }
                @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    result[0].methods.put(name + " " + desc, new Member(access, name, desc)); return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return result[0];
        }
    }

    private record Member(int access, String name, String descriptor) { }
    private record ResolvedMember(RuntimeClass owner, Member member) { }

    private static final class RuntimeInventory {
        final Map<String, RuntimeClass> classes = new LinkedHashMap<>();
        final Map<String, String> artifactGroups;

        RuntimeInventory(Map<String, RuntimeClass> runtime, Map<String, byte[]> artifact, Map<String, String> artifactGroups) {
            classes.putAll(runtime);
            artifact.forEach((name, bytes) -> classes.put(name, RuntimeClass.read(bytes)));
            this.artifactGroups = Map.copyOf(artifactGroups);
        }

        ResolvedMember field(String owner, String key, Set<String> seen) {
            if (!seen.add(owner)) return null;
            RuntimeClass c = classes.get(owner);
            if (c == null) return null;
            Member declared = c.fields.get(key);
            if (declared != null) return new ResolvedMember(c, declared);
            for (String i : c.interfaces) {
                ResolvedMember found = field(i, key, seen);
                if (found != null) return found;
            }
            return c.parent == null ? null : field(c.parent, key, seen);
        }

        ResolvedMember method(String owner, String key, boolean constructor, boolean staticCall, Set<String> seen) {
            if (!seen.add(owner)) return null;
            RuntimeClass c = classes.get(owner);
            if (c == null) return null;
            Member declared = c.methods.get(key);
            if (declared != null) return new ResolvedMember(c, declared);
            if (constructor) return null;
            if (!c.isInterface && c.parent != null) {
                ResolvedMember found = method(c.parent, key, false, staticCall, seen);
                if (found != null) return found;
            }
            if (!staticCall) {
                for (String i : c.interfaces) {
                    ResolvedMember found = method(i, key, false, false, seen);
                    if (found != null) return found;
                }
                if (c.isInterface) {
                    RuntimeClass object = classes.get("java/lang/Object");
                    Member objectMethod = object == null ? null : object.methods.get(key);
                    if (objectMethod != null && (objectMethod.access() & Opcodes.ACC_PUBLIC) != 0
                            && (objectMethod.access() & Opcodes.ACC_STATIC) == 0) {
                        return new ResolvedMember(object, objectMethod);
                    }
                }
            }
            return null;
        }

        boolean subtype(String child, String parent, Set<String> seen) {
            if (child.equals(parent)) return true;
            if (!seen.add(child)) return false;
            RuntimeClass c = classes.get(child);
            if (c == null) return false;
            if (c.parent != null && subtype(c.parent, parent, seen)) return true;
            for (String i : c.interfaces) if (subtype(i, parent, seen)) return true;
            return false;
        }

        boolean nestmates(String aName, String bName) {
            RuntimeClass a = classes.get(aName), b = classes.get(bName);
            if (a == null || b == null || a.major < 55 || b.major < 55
                    || !sameLoader(aName, bName)) return false;
            String aHost = a.nestHost == null ? a.name : a.nestHost;
            String bHost = b.nestHost == null ? b.name : b.nestHost;
            if (!aHost.equals(bHost) || !packageName(a.name).equals(packageName(b.name))) return false;
            RuntimeClass host = classes.get(aHost);
            if (host == null || host.major < 55 || host.nestHost != null) return false;
            return (a.name.equals(aHost) || host.nestMembers.contains(a.name))
                    && (b.name.equals(bHost) || host.nestMembers.contains(b.name));
        }

        boolean sameLoader(String a, String b) {
            String aGroup = artifactGroups.getOrDefault(a, "anchor");
            String bGroup = artifactGroups.getOrDefault(b, "anchor");
            return aGroup.equals(bGroup);
        }

        boolean sameRuntimePackage(String a, String b) {
            return packageName(a).equals(packageName(b)) && sameLoader(a, b);
        }
    }

    private static final class Scan extends ClassVisitor {
        final String caller;
        final RuntimeInventory inventory;
        final Map<String, String> mappings;
        final List<String> mappedPrefixes;
        final List<String> failures;
        int classReferences;
        int memberReferences;

        Scan(String caller, RuntimeInventory inventory, Map<String, String> mappings, List<String> mappedPrefixes, List<String> failures) {
            super(Opcodes.ASM9); this.caller = caller; this.inventory = inventory;
            this.mappings = mappings; this.mappedPrefixes = mappedPrefixes; this.failures = failures;
        }

        void type(Type type, String where) {
            if (type.getSort() == Type.ARRAY) type(type.getElementType(), where);
            else if (type.getSort() == Type.OBJECT) classRef(type.getInternalName(), where);
            else if (type.getSort() == Type.METHOD) {
                for (Type arg : type.getArgumentTypes()) type(arg, where);
                type(type.getReturnType(), where);
            }
        }

        void classRef(String name, String where) {
            if (name == null) return;
            if (name.startsWith("[")) {
                type(Type.getType(name), where);
                return;
            }
            classReferences++;
            checkMappedIdentity(name, where);
            if (!inventory.classes.containsKey(name)) {
                failures.add(where + " references absent runtime class " + name);
                return;
            }
            RuntimeClass target = inventory.classes.get(name);
            if ((target.access & Opcodes.ACC_PUBLIC) == 0 && !inventory.sameRuntimePackage(caller, name)) {
                failures.add(where + " references non-public runtime class " + name + " from another package");
            }
        }

        private void checkMappedIdentity(String name, String where) {
            for (String prefix : mappedPrefixes) {
                if (!name.startsWith(prefix)) continue;
                String target = mappings.get(name);
                if (target == null) {
                    failures.add(where + " references class " + name + " absent from the declared Tiny mapping");
                } else if (!target.equals(name)) {
                    failures.add(where + " retains source-namespace class " + name + " (runtime name is " + target + ")");
                }
            }
        }

        void member(int opcode, boolean method, String owner, String name, String descriptor, boolean itf, String where) {
            classRef(owner, where);
            type(method ? Type.getMethodType(descriptor) : Type.getType(descriptor), where);
            if (owner.startsWith("[")) {
                memberReferences++;
                if (!method || opcode != Opcodes.INVOKEVIRTUAL || itf || name.equals("<init>") || name.equals("<clinit>")) {
                    failures.add(where + " uses an invalid member operation for array owner " + owner + "." + name + descriptor);
                    return;
                }
                if (name.equals("clone") && descriptor.equals("()Ljava/lang/Object;")) return;
                ResolvedMember resolved = inventory.method("java/lang/Object", name + " " + descriptor, false, false, new HashSet<>());
                if (resolved == null) {
                    failures.add(where + " references absent runtime array method " + owner + "." + name + descriptor);
                    return;
                }
                if ((resolved.member.access() & Opcodes.ACC_STATIC) != 0) {
                    failures.add(where + " uses instance access for static array member " + resolved.owner.name + "." + name + descriptor);
                }
                checkAccess(owner, resolved.owner, resolved.member, opcode, where);
                return;
            }
            RuntimeClass ownerClass = inventory.classes.get(owner);
            if (ownerClass == null) return;
            if ((opcode == Opcodes.INVOKEINTERFACE) != ownerClass.isInterface
                    && (opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)) {
                failures.add(where + " uses " + (opcode == Opcodes.INVOKEINTERFACE ? "invokeinterface" : "invokevirtual")
                        + " for the wrong owner kind " + owner);
            }
            memberReferences++;
            boolean staticUse = method ? opcode == Opcodes.INVOKESTATIC : opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC;
            boolean constructor = method && name.equals("<init>");
            ResolvedMember resolved = method
                    ? inventory.method(owner, name + " " + descriptor, constructor, staticUse, new HashSet<>())
                    : inventory.field(owner, name + " " + descriptor, new HashSet<>());
            if (resolved == null) {
                failures.add(where + " references absent runtime " + (method ? "method " : "field ") + owner + "." + name + descriptor);
                return;
            }
            boolean actualStatic = (resolved.member.access() & Opcodes.ACC_STATIC) != 0;
            if (actualStatic != staticUse) failures.add(where + " uses " + (staticUse ? "static" : "instance")
                    + " access for " + (actualStatic ? "static" : "instance") + " member " + resolved.owner.name + "." + name + descriptor);
            if (method && ownerClass.isInterface != itf) failures.add(where + " has inconsistent interface owner flag for " + owner + "." + name + descriptor);
            if (method && constructor && opcode != Opcodes.INVOKESPECIAL) failures.add(where + " invokes constructor without invokespecial: " + owner + descriptor);
            if (method && constructor && !resolved.owner.name.equals(owner)) failures.add(where + " resolves constructor outside its owner: " + owner + "." + descriptor);
            checkAccess(owner, resolved.owner, resolved.member, opcode, where);
        }

        private void checkAccess(String symbolicOwner, RuntimeClass declaring, Member member, int opcode, String where) {
            int access = member.access();
            if ((access & Opcodes.ACC_PRIVATE) != 0) {
                if (!caller.equals(declaring.name) && !inventory.nestmates(caller, declaring.name)) {
                    failures.add(where + " cannot access private member " + declaring.name + "." + member.name() + member.descriptor());
                }
                return;
            }
            if ((access & Opcodes.ACC_PUBLIC) != 0 || inventory.sameRuntimePackage(caller, declaring.name)) return;
            if ((access & Opcodes.ACC_PROTECTED) != 0 && inventory.subtype(caller, declaring.name, new HashSet<>())) {
                boolean staticMember = (access & Opcodes.ACC_STATIC) != 0;
                if (staticMember || opcode == Opcodes.INVOKESPECIAL || inventory.subtype(symbolicOwner, caller, new HashSet<>())) return;
            }
            failures.add(where + " cannot prove package/protected access to " + declaring.name + "." + member.name() + member.descriptor());
        }

        @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
            checkMappedIdentity(name, name + " definition");
            if (parent != null) classRef(parent, name);
            if (interfaces != null) for (String i : interfaces) classRef(i, name);
        }
        @Override public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            type(Type.getType(desc), caller + "." + name);
            String where = caller + "." + name;
            return new FieldVisitor(Opcodes.ASM9) {
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    type(Type.getType(descriptor), where); return annotationVisitor(where);
                }
                @Override public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath path, String descriptor, boolean visible) {
                    type(Type.getType(descriptor), where); return annotationVisitor(where);
                }
            };
        }
        @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            String where = caller + "#" + name;
            type(Type.getMethodType(desc), where);
            if (exceptions != null) for (String e : exceptions) classRef(e, where);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override public void visitFrame(int frameType, int numLocal, Object[] local, int numStack, Object[] stack) {
                    frameTypes(local, numLocal, where);
                    frameTypes(stack, numStack, where);
                }
                @Override public void visitTypeInsn(int opcode, String type) {
                    if (type.startsWith("[")) type(Type.getType(type), where);
                    else classRef(type, where);
                }
                @Override public void visitFieldInsn(int op, String owner, String n, String d) { member(op, false, owner, n, d, false, where); }
                @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) { member(op, true, owner, n, d, itf, where); }
                @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                    type(Type.getMethodType(d), where); handle(bsm, where); for (Object arg : args) constant(arg, where);
                }
                @Override public void visitLdcInsn(Object value) { constant(value, where); }
                @Override public void visitMultiANewArrayInsn(String d, int dims) { type(Type.getType(d), where); }
                @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { classRef(type, where); }
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    type(Type.getType(descriptor), where); return annotationVisitor(where);
                }
                @Override public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath path,
                        String descriptor, boolean visible) {
                    type(Type.getType(descriptor), where); return annotationVisitor(where);
                }
                @Override public org.objectweb.asm.AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) {
                    type(Type.getType(descriptor), where); return annotationVisitor(where);
                }
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotationDefault() { return annotationVisitor(where); }
            };
        }

        private void frameTypes(Object[] frame, int count, String where) {
            if (frame == null) return;
            for (int i = 0; i < count; i++) {
                Object value = frame[i];
                if (!(value instanceof String name)) continue;
                if (name.startsWith("[")) type(Type.getType(name), where);
                else classRef(name, where);
            }
        }

        @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            type(Type.getType(descriptor), caller); return annotationVisitor(caller);
        }
        @Override public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath path,
                String descriptor, boolean visible) {
            type(Type.getType(descriptor), caller); return annotationVisitor(caller);
        }
        @Override public void visitNestHost(String nestHost) { classRef(nestHost, caller); }
        @Override public void visitNestMember(String nestMember) { classRef(nestMember, caller); }
        @Override public void visitPermittedSubclass(String permittedSubclass) { classRef(permittedSubclass, caller); }
        @Override public org.objectweb.asm.RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
            String where = caller + "." + name;
            type(Type.getType(descriptor), where);
            return new org.objectweb.asm.RecordComponentVisitor(Opcodes.ASM9) {
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    type(Type.getType(annotation), where); return annotationVisitor(where);
                }
                @Override public org.objectweb.asm.AnnotationVisitor visitTypeAnnotation(int typeRef, org.objectweb.asm.TypePath path,
                        String annotation, boolean visible) {
                    type(Type.getType(annotation), where); return annotationVisitor(where);
                }
            };
        }
        private org.objectweb.asm.AnnotationVisitor annotationVisitor(String where) {
            return new org.objectweb.asm.AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String name, Object value) { constant(value, where); }
                @Override public void visitEnum(String name, String descriptor, String value) { type(Type.getType(descriptor), where); }
                @Override public org.objectweb.asm.AnnotationVisitor visitAnnotation(String name, String descriptor) { type(Type.getType(descriptor), where); return this; }
                @Override public org.objectweb.asm.AnnotationVisitor visitArray(String name) { return this; }
            };
        }
        private void handle(Handle h, String where) {
            boolean constructor = h.getName().equals("<init>");
            if (constructor != (h.getTag() == Opcodes.H_NEWINVOKESPECIAL)) {
                failures.add(where + " uses an invalid method-handle tag for " + h.getOwner() + "." + h.getName() + h.getDesc());
            }
            int op = switch (h.getTag()) {
                case Opcodes.H_GETFIELD -> Opcodes.GETFIELD; case Opcodes.H_GETSTATIC -> Opcodes.GETSTATIC;
                case Opcodes.H_PUTFIELD -> Opcodes.PUTFIELD; case Opcodes.H_PUTSTATIC -> Opcodes.PUTSTATIC;
                case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC; case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
                case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE; default -> Opcodes.INVOKEVIRTUAL;
            };
            member(op, h.getTag() >= Opcodes.H_INVOKEVIRTUAL, h.getOwner(), h.getName(), h.getDesc(), h.isInterface(), where);
        }
        private void constant(Object value, String where) {
            if (value instanceof Type t) type(t, where);
            else if (value instanceof Handle h) handle(h, where);
            else if (value instanceof org.objectweb.asm.ConstantDynamic c) {
                type(Type.getType(c.getDescriptor()), where); handle(c.getBootstrapMethod(), where);
                for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) constant(c.getBootstrapMethodArgument(i), where);
            }
        }
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
