package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The linkage gate's self-tests (docs/decisions/0010), in {@link SelfTest}'s style: a planted reference that does not exist on
 * one version of a range must fail the gate by name, beside the artifacts and ranges it must let stand. The artifacts are
 * written class by class, and the name data is built here and served from disk, so nothing reads the network.
 */
final class LinkageSelfTest {
    private LinkageSelfTest() {
    }

    private static final String WORLD = "net/minecraft/world/World";
    private static final String BASE = "net/minecraft/command/CommandBase";

    /** Searge data for a release that has everything the artifact reaches. */
    private static final String SRG_FULL = String.join("\n",
            "PK: . net/minecraft/src",
            "CL: a " + WORLD, "CL: b net/minecraft/block/Blocks", "CL: c net/minecraft/block/Block", "CL: d net/minecraft/entity/Gone",
            "CL: e " + BASE,
            "FD: b/a net/minecraft/block/Blocks/field_9_z",
            "MD: a/a (I)V " + WORLD + "/func_1_a (I)V",
            "MD: e/b ()V " + BASE + "/func_71_b ()V",
            "MD: e/c ()V " + BASE + "/toString ()V") + "\n";

    static void run(Path root) throws IOException {
        references();
        names();
        inventory();
        data();
        cache();
        gate(root);
        exclusions(root);
        bundle();
        refresh(root);
    }

    // --- the game's own classes ------------------------------------------------------------------------------------------

    /** A class file with the given superclass, interfaces, fields ({@code name desc}) and methods ({@code name desc}). */
    private static byte[] clazz(String name, String superName, String[] interfaces, String[] fields, String[] methods) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
        for (String f : fields) {
            cw.visitField(Opcodes.ACC_PUBLIC, f.substring(0, f.indexOf(' ')), f.substring(f.indexOf(' ') + 1), null, null).visitEnd();
        }
        for (String m : methods) {
            MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m.substring(0, m.indexOf(' ')), m.substring(m.indexOf(' ') + 1), null, null);
            mv.visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static final String MAPPINGS = String.join("\n",
            "# a comment line",
            "net.minecraft.world.Level -> a:",
            "net.minecraft.world.entity.Entity -> b:",
            "    int age -> a",
            "    net.minecraft.world.Level level -> b",
            "    1:2:void tick() -> a",
            "    void tickNothing(int[],java.lang.String) -> c",
            "net.minecraft.server.Player -> c:",
            "    3:3:void enter(net.minecraft.world.Level) -> b",
            "    3:4:void <init>(net.minecraft.world.Level) -> <init>",
            "net.minecraft.Lib -> d:",
            "net.minecraft.Thing -> e:",
            "    void run() -> a",
            "net.minecraft.Impl -> f:") + "\n";

    private static Map<String, byte[]> obfuscated() {
        Map<String, byte[]> g = new LinkedHashMap<>();
        g.put("a", clazz("a", "java/lang/Object", null, new String[0], new String[0]));
        g.put("b", clazz("b", "java/lang/Object", null, new String[] {"a I", "b La;"}, new String[] {"a ()V", "c ([ILjava/lang/String;)V"}));
        g.put("c", clazz("c", "b", null, new String[0], new String[] {"b (La;)V", "<init> (La;)V"}));
        g.put("d", clazz("d", "com/example/Library", null, new String[0], new String[0]));
        g.put("e", clazz("e", "java/lang/Object", null, new String[0], new String[] {"a ()V"}));
        g.put("f", clazz("f", "java/lang/Object", new String[] {"e"}, new String[0], new String[0]));
        g.put("com/example/Other", clazz("com/example/Other", "java/lang/Object", null, new String[0], new String[0]));
        return g;
    }

    static void inventory() throws IOException {
        List<String> owns = List.of("net/minecraft/");
        Inventory game = Inventory.over("official test", obfuscated(), MAPPINGS, owns);
        String entity = "net/minecraft/world/entity/Entity";
        String player = "net/minecraft/server/Player";
        String level = "Lnet/minecraft/world/Level;";
        SelfTest.that(game.hasClass(player) == Names.Verdict.PRESENT && game.hasClass("c") == Names.Verdict.ABSENT
                        && game.hasClass("com/example/Other") == Names.Verdict.ABSENT,
                "linkage:Mojang's mappings give every obfuscated class its official name, and a class under no owned prefix is not inventoried");
        SelfTest.that(game.hasMethod(List.of(player), "tick", "()V") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of(entity), "tickNothing", "([ILjava/lang/String;)V") == Names.Verdict.PRESENT,
                "linkage:a method is named by the mappings (an inherited one on the subclass), its descriptor in official types, arrays and primitives included");
        SelfTest.that(game.hasMethod(List.of(player), "enter", "(" + level + ")V") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of(player), "enter", "(Lnet/minecraft/world/entity/Entity;)V") == Names.Verdict.ABSENT,
                "linkage:a method whose parameter is another class is a different method");
        SelfTest.that(game.hasField(List.of(player), "age", "I") == Names.Verdict.PRESENT
                        && game.hasField(List.of(player), "level", level) == Names.Verdict.PRESENT
                        && game.hasField(List.of(player), "age", "J") == Names.Verdict.ABSENT,
                "linkage:a field is found through the hierarchy by its official name and descriptor");
        SelfTest.that(game.hasMethod(List.of(player), "<init>", "(" + level + ")V") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of(player), "<init>", "()V") == Names.Verdict.ABSENT,
                "linkage:a constructor is held, which Searge's data cannot say");
        SelfTest.that(game.hasMethod(List.of(player), "gone", "()V") == Names.Verdict.ABSENT,
                "linkage:a method no class in the hierarchy has is absent");
        SelfTest.that(game.hasMethod(List.of(player), "toString", "()Ljava/lang/String;") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of("net/minecraft/Impl"), "run", "()V") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of("net/minecraft/Thing"), "hashCode", "()I") == Names.Verdict.PRESENT,
                "linkage:Object's methods, and the methods of an interface the class implements, are found");
        SelfTest.that(game.hasMethod(List.of("net/minecraft/Lib"), "anything", "()V") == Names.Verdict.UNKNOWN,
                "linkage:a hierarchy that leaves the inventory (a library's class) cannot say a member is missing");
        SelfTest.that(game.hasMethod(List.of("net/minecraft/Lib", player), "tick", "()V") == Names.Verdict.PRESENT
                        && game.hasMethod(List.of("net/minecraft/Impl", player), "gone", "()V") == Names.Verdict.ABSENT,
                "linkage:a member an artifact's own class inherits is held to each class it might come from");
        SelfTest.that(SelfTest.refuses(() -> {
            try {
                Inventory.over("empty", Map.of("x", clazz("x", "java/lang/Object", null, new String[0], new String[0])), MAPPINGS, owns);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, "holds no class under the owned prefixes"), "linkage:an archive with none of the game's classes is refused, not read as an empty game");
        SelfTest.that(SelfTest.refuses(() -> {
            try {
                Inventory.over("bad", obfuscated(), "this is not a mapping\n", owns);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, "not a ProGuard mapping line"), "linkage:mappings that are not ProGuard's are refused");
        Map<String, byte[]> named = new LinkedHashMap<>();
        named.put("net/minecraft/A", clazz("net/minecraft/A", "java/lang/Enum", null, new String[] {"X Lnet/minecraft/A;"}, new String[] {"values ()[Lnet/minecraft/A;"}));
        Inventory unobfuscated = Inventory.over("unobfuscated test", named);
        SelfTest.that(unobfuscated.hasMethod(List.of("net/minecraft/A"), "values", "()[Lnet/minecraft/A;") == Names.Verdict.PRESENT
                        && unobfuscated.hasMethod(List.of("net/minecraft/A"), "name", "()Ljava/lang/String;") == Names.Verdict.PRESENT
                        && unobfuscated.hasMethod(List.of("net/minecraft/A"), "nope", "()V") == Names.Verdict.ABSENT,
                "linkage:a game in the names it runs under is held with the Java platform's classes at the end of its hierarchy (an enum's name())");
    }

    // --- what an artifact references -----------------------------------------------------------------------------------

    private static Map<String, byte[]> artifact() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("fixture/Mod", mod());
        classes.put("fixture/Helper", helper());
        return classes;
    }

    /** A class extending the game's {@code CommandBase} that reaches a class, a method, a field, a constant and an inherited method. */
    private static byte[] mod() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Mod", null, BASE, null);
        MethodVisitor use = cw.visitMethod(Opcodes.ACC_PUBLIC, "use", "(L" + WORLD + ";)V", null, null);
        use.visitCode();
        use.visitVarInsn(Opcodes.ALOAD, 1);
        use.visitInsn(Opcodes.ICONST_1);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, WORLD, "func_1_a", "(I)V", false);
        use.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/block/Blocks", "field_9_z", "Lnet/minecraft/block/Block;");
        use.visitInsn(Opcodes.POP);
        use.visitLdcInsn(Type.getObjectType("net/minecraft/entity/Gone"));
        use.visitInsn(Opcodes.POP);
        use.visitVarInsn(Opcodes.ALOAD, 0);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Mod", "func_71_b", "()V", false);
        use.visitVarInsn(Opcodes.ALOAD, 0);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Mod", "own", "()V", false);
        use.visitVarInsn(Opcodes.ALOAD, 0);
        use.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Mod", "toString", "()V", false);
        use.visitInsn(Opcodes.RETURN);
        use.visitMaxs(0, 0);
        use.visitEnd();
        MethodVisitor own = cw.visitMethod(Opcodes.ACC_PUBLIC, "own", "()V", null, null);
        own.visitCode();
        own.visitInsn(Opcodes.RETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] helper() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "fixture/Helper", null, "java/lang/Object", null);
        MethodVisitor m = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "fixture/Mod");
        m.visitInsn(Opcodes.POP);
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static boolean has(References.Found found, char kind, String owner, String name) {
        return found.refs().keySet().stream().anyMatch(r -> r.kind() == kind && r.owner().equals(owner) && r.name().equals(name));
    }

    static void references() {
        References.Found found = References.scan(artifact(), java.util.Set.of());
        SelfTest.that(found.classFiles() == 2, "linkage:every class of the artifact is read");
        SelfTest.that(has(found, 'C', WORLD, "") && has(found, 'C', BASE, "") && has(found, 'C', "net/minecraft/entity/Gone", ""),
                "linkage:a class named by a method's signature, a superclass and a constant is a reference: " + found.refs().keySet());
        SelfTest.that(has(found, 'M', WORLD, "func_1_a") && has(found, 'F', "net/minecraft/block/Blocks", "field_9_z"),
                "linkage:a method call and a field read are references");
        SelfTest.that(has(found, 'M', BASE, "func_71_b"),
                "linkage:a method the artifact's class inherits from the game is named on the game's class, where the game must have it");
        SelfTest.that(found.refs().keySet().stream().noneMatch(r -> r.owner().startsWith("fixture/")),
                "linkage:what the artifact defines itself is never a reference outside it (not a class, not a member it declares)");
        SelfTest.that(found.refs().keySet().stream().filter(r -> r.kind() == 'M' && r.name().equals("toString")).count() == 1,
                "linkage:a method the artifact's class does not declare is traced up to the first class outside it");
    }

    // --- the names -------------------------------------------------------------------------------------------------------

    static void names() throws IOException {
        Names srg = Names.Flat.srg("srg test", new ByteArrayInputStream(SRG_FULL.getBytes(StandardCharsets.UTF_8)));
        SelfTest.that(srg.hasClass(WORLD) == Names.Verdict.PRESENT && srg.hasClass("net/minecraft/nothing") == Names.Verdict.ABSENT,
                "linkage:Searge's classes are held to the names after the arrow");
        SelfTest.that(srg.hasMethod(List.of(WORLD), "func_1_a", "(I)V") == Names.Verdict.PRESENT
                        && srg.hasMethod(List.of("net/minecraft/entity/Sub"), "func_1_a", "(I)V") == Names.Verdict.PRESENT,
                "linkage:a numbered method is the game's wherever it is named: a subclass naming an inherited one is the same method");
        SelfTest.that(srg.hasMethod(List.of(WORLD), "func_1_a", "(II)V") == Names.Verdict.ABSENT,
                "linkage:a numbered method whose descriptor changed is absent");
        SelfTest.that(srg.hasMethod(List.of(WORLD), "func_9_z", "(I)V") == Names.Verdict.ABSENT, "linkage:a numbered method the game lacks is absent");
        SelfTest.that(srg.hasMethod(List.of(BASE), "toString", "()V") == Names.Verdict.UNKNOWN
                        && srg.hasMethod(List.of(BASE), "<init>", "()V") == Names.Verdict.UNKNOWN,
                "linkage:a member whose name the data does not number is neither found nor missing");
        SelfTest.that(srg.hasField(List.of("x"), "field_9_z", "Lx;") == Names.Verdict.PRESENT
                        && srg.hasField(List.of("x"), "field_9_y", "Lx;") == Names.Verdict.ABSENT
                        && srg.hasField(List.of("x"), "BLACK", "Lx;") == Names.Verdict.UNKNOWN,
                "linkage:a numbered field is found or missing by its name, any other is unknown");
        SelfTest.that(SelfTest.refuses(() -> {
            try {
                Names.Flat.srg("empty", new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }, "is not a joined.srg"), "linkage:a file that is no Searge data is refused, not read as an empty game");

        String tiny = String.join("\n", "tiny\t2\t0\tofficial\tintermediary",
                "c\ta\tnet/minecraft/class_1",
                "\tf\tLb;\ta\tfield_10",
                "\tm\t(La;I)Lb;\ta\tmethod_20",
                "c\tb\tnet/minecraft/class_2") + "\n";
        Names inter = Names.Flat.tiny("intermediary test", new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)), "intermediary");
        SelfTest.that(inter.hasClass("net/minecraft/class_1") == Names.Verdict.PRESENT && inter.hasClass("a") == Names.Verdict.ABSENT,
                "linkage:a Tiny file's classes are held to the names the artifact runs on, not its first column");
        SelfTest.that(inter.hasMethod(List.of("x"), "method_20", "(Lnet/minecraft/class_1;I)Lnet/minecraft/class_2;") == Names.Verdict.PRESENT
                        && inter.hasMethod(List.of("x"), "method_20", "(La;I)Lb;") == Names.Verdict.ABSENT,
                "linkage:a Tiny method's descriptor is written in the runtime names before it is compared");
        SelfTest.that(inter.hasField(List.of("x"), "field_10", "Lnet/minecraft/class_2;") == Names.Verdict.PRESENT
                        && inter.hasField(List.of("x"), "field_10", "I") == Names.Verdict.ABSENT,
                "linkage:a Tiny field is held to its descriptor as well as its name");
    }

    /** A server bundle, as Mojang ships it from 1.18: the game's jar and its libraries' jars inside one archive. */
    static void bundle() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-bundle");
        try {
            Map<String, byte[]> game = obfuscated();
            Map<String, byte[]> library = new LinkedHashMap<>();
            library.put("com/mojang/brigadier/Dispatcher", clazz("com/mojang/brigadier/Dispatcher", "java/lang/Object", null, new String[0],
                    new String[] {"execute ()V"}));
            library.put("org/apache/Other", clazz("org/apache/Other", "java/lang/Object", null, new String[0], new String[0]));
            Path bundle = dir.resolve("server.jar");
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(bundle))) {
                z.putNextEntry(new ZipEntry("META-INF/versions.list"));
                z.write("abc\t1.0\t1.0/server-1.0.jar\n".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                z.putNextEntry(new ZipEntry("META-INF/versions/1.0/server-1.0.jar"));
                z.write(jarOf(game));
                z.closeEntry();
                z.putNextEntry(new ZipEntry("META-INF/libraries.list"));
                z.write("abc\tcom.mojang:brigadier:1\tcom/mojang/brigadier/brigadier-1.jar\n".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
                z.putNextEntry(new ZipEntry("META-INF/libraries/com/mojang/brigadier/brigadier-1.jar"));
                z.write(jarOf(library));
                z.closeEntry();
            }
            Inventory read = Inventory.of("bundle test", bundle, new ByteArrayInputStream(MAPPINGS.getBytes(StandardCharsets.UTF_8)),
                    List.of("net/minecraft/", "com/mojang/"));
            SelfTest.that(read.hasClass("net/minecraft/server/Player") == Names.Verdict.PRESENT
                            && read.hasClass("com/mojang/brigadier/Dispatcher") == Names.Verdict.PRESENT
                            && read.hasClass("org/apache/Other") == Names.Verdict.ABSENT
                            && read.hasMethod(List.of("com/mojang/brigadier/Dispatcher"), "execute", "()V") == Names.Verdict.PRESENT
                            && read.hasMethod(List.of("net/minecraft/server/Player"), "tick", "()V") == Names.Verdict.PRESENT,
                    "linkage:a server bundle is read as the game's jar with the libraries it ships with, the mappings applied to the game alone");
            Inventory gameOnly = Inventory.of("bundle test", bundle, new ByteArrayInputStream(MAPPINGS.getBytes(StandardCharsets.UTF_8)),
                    List.of("net/minecraft/"));
            SelfTest.that(gameOnly.hasClass("com/mojang/brigadier/Dispatcher") == Names.Verdict.ABSENT,
                    "linkage:a library the entry does not own is not the game's");
        } finally {
            SelfTest.delete(dir);
        }
    }

    private static byte[] jarOf(Map<String, byte[]> classes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey() + ".class"));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** What the artifact chooses at runtime and the gate does not hold. */
    static void exclusions(Path root) throws IOException {
        References.Found all = References.scan(artifact(), java.util.Set.of());
        References.Found part = References.scan(artifact(), java.util.Set.of("fixture/Mod"));
        SelfTest.that(part.excluded() == 1 && part.classFiles() == 1 && all.classFiles() == 2 && all.excluded() == 0,
                "linkage:the classes left out are counted, and the report says how many were read");
        SelfTest.that(part.refs().keySet().stream().noneMatch(r -> r.owner().startsWith("net/minecraft/")) && !all.refs().isEmpty(),
                "linkage:a class left out makes no reference the gate holds");
        // an excluded class is still the artifact's own: a member the held classes name on it is not a reference outside
        Map<String, byte[]> own = new LinkedHashMap<>(artifact());
        References.Found helperOnly = References.scan(own, java.util.Set.of("fixture/Mod"));
        SelfTest.that(helperOnly.refs().keySet().stream().noneMatch(r -> r.owner().equals("fixture/Mod")),
                "linkage:what a held class names on a class left out is the artifact's own, never a reference to the game");
    }

    // --- the lock --------------------------------------------------------------------------------------------------------

    private static String lock(String versions) {
        return "{\"schema\":1,\"namespaces\":{\"srg\":{\"reads\":\"srg-flat\",\"owns\":[\"net/minecraft/\"],\"versions\":{" + versions + "}}}}";
    }

    private static String entry(String version, String url, String sha) {
        return "\"" + version + "\":{\"inputs\":[{\"role\":\"joined\",\"url\":\"" + url + "\",\"sha256\":\"" + sha + "\",\"entry\":\"joined.srg\"}]}";
    }

    static void data() {
        String sha = "a".repeat(64);
        NameData ok = NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)), "t");
        SelfTest.that(ok.entry("srg", "1.8.9").inputs().get(0).sha256().equals(sha) && ok.entry("srg", "1.8.9").owns().equals(List.of("net/minecraft/"))
                        && ok.entry("srg", "1.8.8") == null && ok.versions("srg").equals(List.of("1.8.9")),
                "linkage:a lock reads, an entry takes its namespace's reads and owns, and a version with no entry has none");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "http://example.org/x.zip", sha)), "t"), "must be https"),
                "linkage:a pin over plain http is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", "ABC")), "t"), "sha256 must be 64 lowercase hex"),
                "linkage:a pin that is no SHA-256 is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)).replace("srg-flat", "obj-flat"), "t"),
                "reads 'obj-flat' is not one of"), "linkage:a reader the gate does not have is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)).replace("\"role\":\"joined\"", "\"role\":\"mappings\""), "t"),
                "takes the inputs [joined]"), "linkage:an input the reader does not take is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)).replace("[\"net/minecraft/\"]", "[\"net/minecraft\"]"), "t"),
                "each ending in '/'"), "linkage:an owned prefix that would also own net/minecraftforge is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)).replace("\"srg\":{", "\"mappings\":{"), "t"),
                "'mappings' is not a name the era table knows"), "linkage:a naming the era table does not have is refused");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.x", "https://example.org/x.zip", sha)), "t"), "is not a Minecraft version"),
                "linkage:a line is no version to pin data for");
        SelfTest.that(SelfTest.refuses(() -> NameData.parse(lock(entry("1.8.9", "https://example.org/x.zip", sha)).replace("\"schema\":1", "\"schema\":2"), "t"),
                "schema must be 1"), "linkage:another schema is refused");
    }

    // --- the cache -------------------------------------------------------------------------------------------------------

    static void cache() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-names");
        try {
            byte[] bytes = "joined".getBytes(StandardCharsets.UTF_8);
            Path source = dir.resolve("source.bin");
            Files.write(source, bytes);
            String sha = NameCache.sha256(bytes);
            NameData.Input input = new NameData.Input("joined", source.toUri().toString(), sha, null);
            NameCache cache = new NameCache(dir.resolve("cache"), false);
            SelfTest.that(Files.readString(cache.get(input)).equals("joined"), "linkage:a pinned input is fetched and read back");
            Files.delete(source);
            SelfTest.that(Files.readString(cache.get(input)).equals("joined"), "linkage:an input already cached is read from disk with its source gone");
            SelfTest.that(Files.readString(new NameCache(dir.resolve("cache"), true).get(input)).equals("joined"),
                    "linkage:an offline build reads what an earlier one cached");
            Files.writeString(dir.resolve("cache").resolve(sha), "tampered");
            SelfTest.that(throwsUnavailable(() -> new NameCache(dir.resolve("cache"), true).get(input), "is not in the name cache"),
                    "linkage:a cache entry that no longer hashes to its pin is never used: offline it is missing");
            Files.write(source, bytes);
            SelfTest.that(Files.readString(cache.get(input)).equals("joined"), "linkage:and online it is fetched again");
            NameData.Input wrong = new NameData.Input("joined", source.toUri().toString(), "b".repeat(64), null);
            SelfTest.that(SelfTest.refuses(() -> {
                try {
                    cache.get(wrong);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }, "has SHA-256 " + sha), "linkage:a download that does not match its pin is refused, naming both hashes");
            NameData.Input missing = new NameData.Input("joined", dir.resolve("absent.bin").toUri().toString(), "c".repeat(64), null);
            SelfTest.that(throwsUnavailable(() -> cache.get(missing), "could not fetch"), "linkage:a host that does not answer is unavailable, not wrong");
            SelfTest.that(throwsUnavailable(() -> new NameCache(dir.resolve("cold"), true).get(missing), "is offline"),
                    "linkage:an offline build with nothing cached says so, naming the input");
        } finally {
            SelfTest.delete(dir);
        }
    }

    private interface Fetch {
        void run() throws IOException;
    }

    private static boolean throwsUnavailable(Fetch f, String fragment) {
        try {
            f.run();
            return false;
        } catch (NameCache.Unavailable e) {
            return e.getMessage().contains(fragment);
        } catch (IOException e) {
            return false;
        }
    }

    // --- the gate --------------------------------------------------------------------------------------------------------

    private static byte[] zipOf(String name, String text) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(name));
            z.write(text.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    /** A lock over {@code data}: each version's Searge text, written to {@code dir} and pinned. */
    private static NameData lockOver(Path dir, Map<String, String> data) throws IOException {
        StringBuilder versions = new StringBuilder();
        for (Map.Entry<String, String> e : data.entrySet()) {
            byte[] zip = zipOf("joined.srg", e.getValue());
            Path file = dir.resolve("srg-" + e.getKey() + ".zip");
            Files.write(file, zip);
            if (versions.length() > 0) {
                versions.append(',');
            }
            versions.append(entry(e.getKey(), file.toUri().toString(), NameCache.sha256(zip)));
        }
        return NameData.parse(lock(versions.toString()), "test lock");
    }

    private static JsonObject statusOf(LinkageGate.Result r, String version) {
        for (JsonElement e : r.report().getAsJsonArray("versions")) {
            if (e.getAsJsonObject().get("version").getAsString().equals(version)) {
                return e.getAsJsonObject();
            }
        }
        throw new IllegalStateException("no " + version + " in " + r.report());
    }

    static void gate(Path root) throws IOException {
        Path dir = Files.createTempDirectory("mycelium-linkage");
        try {
            Eras eras = Eras.load(root.toFile());
            References.Found found = References.scan(artifact(), java.util.Set.of());
            ArtifactLedger.Artifact claim = SelfTest.claim("forge", "1.8", "1.8.9", List.of("1.8.9"), List.of());
            JsonObject range = SelfTest.gate(root, claim, null).report();
            NameCache cache = new NameCache(dir.resolve("cache"), false);
            LinkageGate.Loader loader = entry -> Names.read(entry, cache);

            // a release the artifact links on, one that has no name data, and the proven one
            String withoutGone = SRG_FULL.replace("CL: d net/minecraft/entity/Gone\n", "");
            NameData fine = lockOver(dir, Map.of("1.8", SRG_FULL, "1.8.8", SRG_FULL, "1.8.9", SRG_FULL));
            LinkageGate.Result ok = LinkageGate.evaluate(range, claim, null, eras, fine, found, loader, Map.of(), false);
            SelfTest.that(ok.findings().isEmpty(), "linkage:an artifact whose references all resolve on each release that has data holds: " + ok.findings());
            SelfTest.that(statusOf(ok, "1.8").get("status").getAsString().equals("linked")
                            && statusOf(ok, "1.8.8").get("status").getAsString().equals("linked"),
                    "linkage:a release with name data whose references all resolve is linked, and was never run");
            SelfTest.that(statusOf(ok, "1.8.9").get("status").getAsString().equals("proven")
                            && statusOf(ok, "1.8.9").getAsJsonObject("linkage").get("status").getAsString().equals("linked"),
                    "linkage:a proven release stays proven and is linked as well");
            JsonObject none = statusOf(ok, "1.8.4");
            SelfTest.that(none.get("status").getAsString().equals("interpolated")
                            && none.getAsJsonObject("linkage").get("why").getAsString().contains("no name data is pinned for srg 1.8.4"),
                    "linkage:a release with no name data stays interpolated, and says why: " + none);
            JsonObject counts = ok.report().getAsJsonObject("counts");
            SelfTest.that(counts.get("proven").getAsInt() == 1 && counts.get("linked").getAsInt() == 2 && counts.get("interpolated").getAsInt() == 7
                            && counts.get("pending").getAsInt() == 0,
                    "linkage:the counts are proven, pending, linked and interpolated: " + counts);
            JsonObject checked = statusOf(ok, "1.8").getAsJsonObject("linkage").getAsJsonObject("checked");
            SelfTest.that(checked.get("classes").getAsInt() > 0 && checked.get("methods").getAsInt() == 2 && checked.get("fields").getAsInt() == 1
                            && checked.get("notNumbered").getAsInt() == 1,
                    "linkage:the report counts what was held and what the data cannot number: " + checked);

            // the planted reference: a class the artifact uses that one release of the range does not have
            NameData brokenAt18 = lockOver(dir, Map.of("1.8", withoutGone, "1.8.8", SRG_FULL, "1.8.9", SRG_FULL));
            LinkageGate.Result bad = LinkageGate.evaluate(range, claim, null, eras, brokenAt18, found, loader, Map.of(), false);
            SelfTest.that(bad.findings().size() == 1 && bad.findings().get(0).startsWith("1.8:")
                            && bad.findings().get(0).contains("class net/minecraft/entity/Gone")
                            && bad.findings().get(0).contains("fixture/Mod#use"),
                    "linkage:a class absent on one release of the range fails there, naming the class, the version and where it is used: " + bad.findings());
            SelfTest.that(statusOf(bad, "1.8").getAsJsonObject("linkage").get("status").getAsString().equals("broken")
                            && statusOf(bad, "1.8.8").getAsJsonObject("linkage").get("status").getAsString().equals("linked"),
                    "linkage:only that release is broken");

            // a method whose descriptor changed, and a field the release lacks
            NameData changed = lockOver(dir, Map.of("1.8.8", SRG_FULL.replace("func_1_a (I)V", "func_1_a (II)V"),
                    "1.8.9", SRG_FULL.replace("FD: b/a net/minecraft/block/Blocks/field_9_z\n", "")));
            LinkageGate.Result moved = LinkageGate.evaluate(range, claim, null, eras, changed, found, loader, Map.of(), false);
            SelfTest.that(moved.findings().size() == 2
                            && moved.findings().stream().anyMatch(f -> f.startsWith("1.8.8:") && f.contains("method net/minecraft/world/World.func_1_a(I)V"))
                            && moved.findings().stream().anyMatch(f -> f.startsWith("1.8.9:") && f.contains("field net/minecraft/block/Blocks.field_9_z")),
                    "linkage:a method whose descriptor changed and a field that is gone each fail on their own release, the proven one too: "
                            + moved.findings());

            // an inherited method the game's class no longer has
            NameData inherited = lockOver(dir, Map.of("1.8.8", SRG_FULL.replace("MD: e/b ()V " + BASE + "/func_71_b ()V\n", "")));
            LinkageGate.Result lost = LinkageGate.evaluate(range, claim, null, eras, inherited, found, loader, Map.of(), false);
            SelfTest.that(lost.findings().size() == 1 && lost.findings().get(0).contains("method " + BASE + ".func_71_b()V"),
                    "linkage:a method inherited from the game's class is held to the game's class: " + lost.findings());

            // a version that already holds a capsule's own runtime linkage
            LinkageGate.Result byRuntime = LinkageGate.evaluate(range, claim, null, eras, lockOver(dir, Map.of()), found, loader,
                    Map.of("1.8", "a runtime view"), false);
            SelfTest.that(byRuntime.findings().isEmpty() && statusOf(byRuntime, "1.8").get("status").getAsString().equals("linked")
                            && statusOf(byRuntime, "1.8").getAsJsonObject("linkage").get("by").getAsString().equals("a runtime view"),
                    "linkage:a version linked by a runtime the build ran against is linked, and says by what");

            // data that is pinned and cannot be had: a failure online, a record offline
            NameData gone = lockOver(dir, Map.of("1.8", SRG_FULL));
            Files.delete(dir.resolve("srg-1.8.zip"));
            LinkageGate.Loader cold = entry -> Names.read(entry, new NameCache(dir.resolve("cold"), false));
            LinkageGate.Result online = LinkageGate.evaluate(range, claim, null, eras, gone, found, cold, Map.of(), false);
            SelfTest.that(online.findings().size() == 1 && online.findings().get(0).startsWith("1.8:") && online.findings().get(0).contains("could not be had"),
                    "linkage:pinned name data that cannot be had fails an online build, naming the version: " + online.findings());
            LinkageGate.Result offline = LinkageGate.evaluate(range, claim, null, eras, gone, found,
                    entry -> Names.read(entry, new NameCache(dir.resolve("cold"), true)), Map.of(), true);
            SelfTest.that(offline.findings().isEmpty() && statusOf(offline, "1.8").getAsJsonObject("linkage").get("status").getAsString().equals("unavailable")
                            && statusOf(offline, "1.8").get("status").getAsString().equals("interpolated"),
                    "linkage:offline, data that is not cached is recorded as unavailable and the version stays interpolated: " + offline.report());

            // a loader the era table names no runtime for
            ArtifactLedger.Artifact agent = SelfTest.claim("javaagent", "a1.2.6", "a1.2.6", List.of("a1.2.6"), List.of());
            JsonObject agentRange = SelfTest.gate(root, agent, null).report();
            JsonObject none2 = statusOf(LinkageGate.evaluate(agentRange, agent, null, eras, fine, found, loader, Map.of(), false), "a1.2.6");
            SelfTest.that(none2.get("status").getAsString().equals("proven") && none2.getAsJsonObject("linkage").get("status").getAsString().equals("none"),
                    "linkage:a version no name data covers is not linked, however well the rest of the build went");
            JsonArray versions = range.getAsJsonArray("versions");
            SelfTest.that(versions.size() == 10, "linkage:the range report the gate reads lists every release of 1.8 to 1.8.9");
        } finally {
            SelfTest.delete(dir);
        }
    }

    // --- refreshing the lock ---------------------------------------------------------------------------------------------

    private interface Throwing {
        void run() throws IOException;
    }

    /** What {@code call} throws, as its class and message, or an empty string when it returns. */
    private static String thrown(Throwing call) {
        try {
            call.run();
            return "";
        } catch (IOException | RuntimeException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static NameRefresh.Fetch served(Map<String, String> files) {
        return url -> {
            String body = files.get(url);
            if (body == null) {
                throw new NameRefresh.NotPublished(url + " answered 404");
            }
            return body.getBytes(StandardCharsets.UTF_8);
        };
    }

    private static JsonObject object(String json) {
        return new com.google.gson.Gson().fromJson(json, JsonObject.class);
    }

    static void refresh(Path root) throws IOException {
        String text = Files.readString(root.resolve("eras/names.json"), StandardCharsets.UTF_8);
        SelfTest.that(NameRefresh.render(object(text)).equals(text), "linkage:the lock is kept in the form myceliumRefreshNames writes it");

        String manifest = RefreshVersionsTask.MANIFEST;
        String snap = "https://example.org/spigot/{version}-R0.1-SNAPSHOT/";
        String snap94 = snap.replace("{version}", "1.9.4");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("https://example.org/mcp/1.9.4/joined.zip", "srg 1.9.4");
        files.put(manifest, "{\"versions\":[{\"id\":\"1.21.2\",\"url\":\"https://example.org/v/1.21.2.json\"},"
                + "{\"id\":\"1.99\",\"url\":\"https://example.org/v/1.99.json\"}]}");
        files.put("https://example.org/v/1.21.2.json", "{\"downloads\":{\"server\":{\"url\":\"https://example.org/d/server.jar\"},"
                + "\"server_mappings\":{\"url\":\"https://example.org/d/server.txt\"}}}");
        files.put("https://example.org/v/1.99.json", "{\"downloads\":{\"server\":{\"url\":\"https://example.org/d/server99.jar\"}}}");
        files.put("https://example.org/d/server.jar", "bundle");
        files.put("https://example.org/d/server.txt", "mappings");
        files.put("https://example.org/d/server99.jar", "bundle 99");
        files.put(snap94 + "maven-metadata.xml", "<metadata><versioning><snapshotVersions>"
                + "<snapshotVersion><extension>jar</extension><value>1.9.4-R0.1-20160101.000001-1</value><updated>20160101000001</updated></snapshotVersion>"
                + "<snapshotVersion><classifier>sources</classifier><extension>jar</extension><value>1.9.4-R0.1-20160301.000001-3</value><updated>20160301000001</updated></snapshotVersion>"
                + "<snapshotVersion><extension>jar</extension><value>1.9.4-R0.1-20160201.000001-2</value><updated>20160201000001</updated></snapshotVersion>"
                + "</snapshotVersions></versioning></metadata>");
        files.put(snap94 + "spigot-api-1.9.4-R0.1-20160201.000001-2.jar", "api 1.9.4");
        NameRefresh.Fetch fetch = served(files);

        JsonObject url = object("{\"kind\":\"url\",\"url\":\"https://example.org/mcp/{version}/joined.zip\",\"role\":\"joined\",\"entry\":\"joined.srg\"}");
        List<NameData.Input> srg = NameRefresh.resolve(url, "1.9.4", fetch);
        SelfTest.that(srg.size() == 1 && srg.get(0).role().equals("joined") && srg.get(0).entry().equals("joined.srg")
                        && srg.get(0).sha256().equals(NameCache.sha256("srg 1.9.4".getBytes(StandardCharsets.UTF_8))),
                "linkage:a url resolver pins the file it finds by its SHA-256");
        SelfTest.that(thrown(() -> NameRefresh.resolve(url, "1.9.3", fetch)).startsWith("NotPublished"),
                "linkage:a version nothing is published for is told apart from a failure");

        JsonObject mojang = object("{\"kind\":\"mojang\",\"game\":\"server\",\"mappings\":\"server_mappings\"}");
        List<NameData.Input> official = NameRefresh.resolve(mojang, "1.21.2", fetch);
        SelfTest.that(official.size() == 2 && official.get(0).role().equals("game") && official.get(1).role().equals("mappings")
                        && official.get(1).url().equals("https://example.org/d/server.txt"),
                "linkage:Mojang's manifest gives a version's server bundle and its mappings");
        SelfTest.that(thrown(() -> NameRefresh.resolve(mojang, "1.99", fetch)).startsWith("NotPublished")
                        && thrown(() -> NameRefresh.resolve(mojang, "1.0", fetch)).startsWith("NotPublished"),
                "linkage:a version without mappings, or one the manifest does not list, gets no entry");

        JsonObject maven = object("{\"kind\":\"maven-snapshot\",\"base\":\"" + snap + "\",\"artifact\":\"spigot-api\"}");
        List<NameData.Input> api = NameRefresh.resolve(maven, "1.9.4", fetch);
        SelfTest.that(api.size() == 1 && api.get(0).url().endsWith("spigot-api-1.9.4-R0.1-20160201.000001-2.jar"),
                "linkage:a snapshot directory gives its newest plain jar, not a classifier's");
        SelfTest.that(thrown(() -> NameRefresh.resolve(object("{\"kind\":\"none\"}"), "1.9.4", fetch)).contains("pinned by hand"),
                "linkage:a namespace pinned by hand is not resolved");

        JsonObject lock = object(text);
        NameRefresh.add(lock, "srg", "1.9.3", srg);
        String grown = NameRefresh.render(lock);
        int at = grown.indexOf("\"1.9.3\"");
        SelfTest.that(at > grown.indexOf("\"1.9.2\"") && at < grown.indexOf("\"1.9.4\"") && NameData.parse(grown, "t").entry("srg", "1.9.3") != null,
                "linkage:a pinned version is written where its place in the version order is, and the lock reads again");
        SelfTest.that(thrown(() -> NameRefresh.add(lock, "srg", "1.9.3", srg)).contains("is already pinned"),
                "linkage:an entry that is pinned is never replaced");

        JsonObject own = object(lock(entry("1.9.4", "https://example.org/mcp/1.9.4/joined.zip", NameCache.sha256("srg 1.9.4".getBytes(StandardCharsets.UTF_8)))));
        SelfTest.that(NameRefresh.drift(own, java.util.Set.of("srg"), fetch).isEmpty(), "linkage:a file that is still the pinned one is not drift");
        files.put("https://example.org/mcp/1.9.4/joined.zip", "srg 1.9.4, republished");
        List<String> drift = NameRefresh.drift(own, java.util.Set.of("srg"), fetch);
        SelfTest.that(drift.size() == 1 && drift.get(0).startsWith("srg 1.9.4 joined: https://example.org/mcp/1.9.4/joined.zip is now "),
                "linkage:a file that changed under its pin is reported by namespace, version and url");
        files.remove("https://example.org/mcp/1.9.4/joined.zip");
        SelfTest.that(NameRefresh.drift(own, java.util.Set.of("srg"), fetch).get(0).contains("cannot be read"),
                "linkage:a file that is no longer published is reported too");
    }
}
