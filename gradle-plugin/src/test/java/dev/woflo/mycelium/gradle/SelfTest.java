package dev.woflo.mycelium.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The plugin's self-tests, in the repository's own dependency-free style (a main, labelled checks, exit code = failures;
 * see the harness's {@code Suite} and {@code SeededIdentity}). Run by {@code ./gradlew -p gradle-plugin selfTest}, and
 * by the Mycelium root's {@code check}.
 *
 * <p>The repository checks are proven the only honest way: a scratch copy of the repository is broken in one specific
 * way, and the check must name that break (compared with an unbroken copy, so the test holds whatever else the
 * repository states that day). A check never shown to fire is not evidence.
 */
public final class SelfTest {
    private static final List<String> FAILURES = new ArrayList<>();
    private static int assertions;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "..").toAbsolutePath().normalize();
        eras(root);
        registry();
        integrations();
        evidence();
        loaders(root);
        shipping();
        downgrading();
        surface();
        repository(root);
        System.out.println();
        System.out.println("Mycelium plugin self-test: " + (FAILURES.isEmpty() ? "PASS" : "FAIL") + " (" + assertions
                + " assertions, " + FAILURES.size() + " failed)");
        FAILURES.forEach(f -> System.out.println("  - " + f));
        System.exit(FAILURES.size());
    }

    static void that(boolean ok, String label) {
        assertions++;
        if (!ok) {
            FAILURES.add(label);
        }
    }

    // --- eras ------------------------------------------------------------------------------------------------------

    static void eras(Path root) {
        Eras eras = Eras.load(root.toFile());
        String[][] expect = {
                {"c0.0.11a", "classic"}, {"c0.0.13a_03", "classic"}, {"c0.30_01c", "classic"},
                {"a1.0.4", "alpha"}, {"a1.2.6", "alpha"},
                {"b1.0", "beta"}, {"b1.7.3", "beta"}, {"b1.8.1", "beta"}, {"b1.9-pre1", "beta"}, {"b1.9-pre6", "beta"},
                {"1.0", "release"}, {"1.7.10", "release"}, {"1.8", "modpacks"}, {"1.12.2", "modpacks"},
                {"1.13", "flattening"}, {"1.13.2", "flattening"}, {"1.14", "fabric"}, {"1.16.5", "fabric"},
                {"1.17", "java16"}, {"1.17.1", "java16"}, {"1.18", "caves"}, {"1.20.1", "caves"},
                {"1.20.2", "configuration"}, {"1.20.4", "configuration"}, {"1.20.5", "components"},
                {"1.20.5-pre1", "components"}, {"1.21.4", "components"}, {"1.21.11", "components"}, {"1.21.100", "components"},
                {"26.1", "unobfuscated"}, {"26.1.2", "unobfuscated"}, {"26.3", "unobfuscated"}, {"26.99", "unobfuscated"},
        };
        for (String[] e : expect) {
            String got;
            try {
                got = eras.of(e[0]).id();
            } catch (RuntimeException ex) {
                got = "throws: " + ex.getMessage();
            }
            that(e[1].equals(got), "eras:of " + e[0] + " is " + e[1] + ", got " + got);
        }
        that(eras.allJavas().equals(List.of(8, 16, 17, 21, 25)), "eras:allJavas is 8,16,17,21,25: " + eras.allJavas());
        String[] order = {"c0.0.11a", "a1.0.4", "b1.0", "1.0", "1.21.1", "26.3"};
        for (int i = 0; i < order.length; i++) {
            for (int j = 0; j < order.length; j++) {
                int c = Eras.compare(order[i], order[j]);
                that(Integer.signum(c) == Integer.signum(Integer.compare(i, j)), "eras:compare orders " + order[i] + " and " + order[j]);
                that(c == -Eras.compare(order[j], order[i]), "eras:compare is antisymmetric on " + order[i] + ", " + order[j]);
            }
        }
        try {
            eras.of("27.1");
            that(false, "eras:a version past every era is a miss");
        } catch (IllegalArgumentException ex) {
            that(ex.getMessage().contains("after 'unobfuscated'"), "eras:a miss names the nearest era: " + ex.getMessage());
        }
        that(!Eras.readable("rd-132211") && !Eras.readable("inf-20100618") && Eras.readable("c0.30_01c"),
                "eras:pre-Classic ids are not readable versions; Classic ones are");
        that(Eras.atMost("26.3", "26.x") && Eras.atMost("26.40.1", "26.x") && !Eras.atMost("27.1", "26.x"), "eras:a line ends after its last version");
        that(Eras.atLeast("a1.0.4", "a1.x") && !Eras.atLeast("c0.30_01c", "a1.x"), "eras:a line starts at its first version");
    }

    // --- the registry ------------------------------------------------------------------------------------------------

    static final String ROW_A = "{\"id\":\"x.a@1\",\"path\":\"a\",\"package\":\"mycelium.a.v1\",\"status\":\"reference\"}";
    static final String ROW_B = "{\"id\":\"x.a@2\",\"path\":\"a-2\",\"package\":\"mycelium.a.v2\",\"status\":\"reference\",\"supersedes\":\"x.a@1\"}";

    static String registryText(String... rows) {
        return "{\"schema\":2,\"note\":\"n\",\"contracts\":[" + String.join(",", rows) + "]}";
    }

    static boolean refuses(Runnable r, String fragment) {
        try {
            r.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(fragment);
        }
    }

    static void registry() {
        that(ContractRegistry.parse(registryText(ROW_A, ROW_B), "t").all().size() == 2, "registry:a valid registry reads");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_A.replace("}", ",\"liveConsumers\":[]}")), "t"), "liveConsumers"),
                "registry:an unknown field is refused, by name");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_A.replace(",\"status\":\"reference\"", "")), "t"), "status"),
                "registry:a row without status is refused");
        that(refuses(() -> ContractRegistry.parse(registryText(ROW_B), "t"), "supersedes unknown"),
                "registry:superseding an unknown contract is refused");
        that(refuses(() -> ContractRegistry.parse("{\"schema\":1,\"contracts\":[]}", "t"), "schema"),
                "registry:another schema is refused");
        ContractRegistry reg = ContractRegistry.parse(registryText(ROW_A, ROW_B), "t");
        that(refuses(() -> reg.resolve(List.of("x.a@1", "x.a@2")), "never both"),
                "registry:a build selecting a contract and its successor is refused, naming both");
        that(reg.resolve(List.of("x.a@2")).size() == 1, "registry:a build selecting the successor alone is fine");
        that(reg.require("x.a@1").runFile().equals("contracts/a/src/mycelium/a/v1/harness/Run.java"),
                "registry:the harness main lives where every runner looks");
    }

    // --- integrations ------------------------------------------------------------------------------------------------

    static void integrations() {
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice|"), "no API packages"),
                "integration:an empty API list is refused, not a gate that keeps nothing out");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat||de.maxhenkel"), "home package"),
                "integration:a blank home package is refused");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice"), "malformed"),
                "integration:a declaration missing its parts is refused");
        that(refuses(() -> IsolationTask.Integration.decode("voicechat|woflo.voice|de maxhenkel"), "not a Java package"),
                "integration:an API that is not a package is refused");
        IsolationTask.Integration ok = IsolationTask.Integration.decode("voicechat|woflo.voice|de.maxhenkel.voicechat|a/B.java");
        that(IsolationTask.Integration.decode(ok.encode()).equals(ok), "integration:encode and decode round-trip");
    }

    // --- the evidence convention ------------------------------------------------------------------------------------

    static void evidence() {
        JsonObject none = EvidenceTask.usageOf(null, "a@1");
        that(!none.get("checked").getAsBoolean() && none.has("why"), "evidence:usage that did not run says so, with why");
        JsonObject empty = EvidenceTask.usageOf(JsonParser.parseString("{\"schema\":2,\"checked\":false,\"why\":\"no sources\"}")
                .getAsJsonObject(), "a@1");
        that(!empty.get("checked").getAsBoolean() && "no sources".equals(empty.get("why").getAsString()),
                "evidence:usage with nothing to judge by says it checked nothing, and why");
        JsonObject seen = EvidenceTask.usageOf(JsonParser.parseString("{\"schema\":2,\"checked\":true,\"results\":"
                + "[{\"id\":\"a@1\",\"referencingFiles\":3}]}").getAsJsonObject(), "a@1");
        that(seen.get("checked").getAsBoolean() && seen.get("referenced").getAsBoolean(), "evidence:usage that ran says what it saw");
    }

    // --- loaders -----------------------------------------------------------------------------------------------------

    static void loaders(Path root) {
        List<String> packages = Loaders.load(root.toFile()).platformPackages();
        for (String p : List.of("net.minecraft", "com.mojang", "net.fabricmc", "net.neoforged", "net.minecraftforge", "cpw.mods",
                "org.spongepowered", "net.legacyfabric", "net.ornithemc", "net.modificationstation", "org.quiltmc")) {
            that(packages.contains(p), "loaders:the pure layer keeps " + p + " out");
        }
    }

    // --- shipping downgraded jars (docs/decisions/0006) ---------------------------------------------------------------

    static void shipping() throws IOException {
        Set<String> pure = Set.of("fixture.ship.kernel");
        that(PureShipTask.inShippablePackage("fixture/ship/kernel/Greeting.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a class in a declared pure package ships");
        that(!PureShipTask.inShippablePackage("fixture/ship/kernelstuff/Other.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a package that merely starts with a pure package's name does not ship (directory-boundary safe)");
        that(!PureShipTask.inShippablePackage("mycelium/harness/Check.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:the shared harness never ships (Hard rule 2), even though it is part of the same bundle");
        that(PureShipTask.inShippablePackage("fixture_ship_mycelium_jvmdg/xyz/wagyourtail/jvmdg/j9/stub/java_base/J_U_List.class",
                pure, "fixture_ship_mycelium_jvmdg"), "shipping:JvmDowngrader's own shaded backport support classes ship too"
                + " (the downgraded bytecode calls them directly)");
        that(!PureShipTask.inShippablePackage("fixture_ship_mycelium_jvmdgsomethingelse/Foo.class", pure, "fixture_ship_mycelium_jvmdg"),
                "shipping:a package that merely starts with the shade prefix's name is not the shade prefix (directory-boundary safe)");

        Path era = Files.createTempDirectory("mycelium-selftest-ship");
        try {
            JsonObject none = PureShipTask.eraGateEvidence(era, "ship-fixture pure layer", 8);
            that(!none.get("checked").getAsBoolean() && none.has("why"), "shipping:no era.json says so, with why");

            String eraJson = "{\"schema\":3,\"java\":8,\"results\":[{\"bundle\":\"ship-fixture pure layer\",\"status\":\"pass\","
                    + "\"bytecode\":\"downgraded to class version 52\",\"outsideReferences\":106,\"unresolved\":0,\"suites\":[]}]}";
            Files.writeString(era.resolve("era.json"), eraJson, StandardCharsets.UTF_8);
            JsonObject found = PureShipTask.eraGateEvidence(era, "ship-fixture pure layer", 8);
            that(found.get("checked").getAsBoolean() && "pass".equals(found.get("status").getAsString())
                    && "downgraded to class version 52".equals(found.get("bytecode").getAsString()),
                    "shipping:the shipped bytes point at the era gate's own result for that exact bundle");

            JsonObject missing = PureShipTask.eraGateEvidence(era, "some other bundle", 8);
            that(!missing.get("checked").getAsBoolean() && missing.get("why").getAsString().contains("some other bundle"),
                    "shipping:a bundle name the era gate never reported on says so, naming it");
        } finally {
            delete(era);
        }
    }

    // --- downgrading the jar's own classes (docs/decisions/0007) --------------------------------------------------------

    static void downgrading() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-selftest-downgrade");
        try {
            // whether a jar needs downgrading is read from its classes' own versions, not from a promise
            Path jar = dir.resolve("in.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
                entry(z, "a/A.class", header(61));
                entry(z, "a/B.class", header(52));
                entry(z, "META-INF/versions/21/a/C.class", header(65));
                entry(z, "notes.txt", "class".getBytes(StandardCharsets.UTF_8));
                entry(z, "pfx/j9/Stub.class", header(52));
            }
            that(DowngradeJarTask.maxMajor(jar) == 61, "downgrade:the newest class version in the jar decides, and a multi-release copy is not the jar's own");
            Path only8 = dir.resolve("only52.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(only8))) {
                entry(z, "a/B.class", header(52));
            }
            that(DowngradeJarTask.maxMajor(only8) == 52, "downgrade:a jar of Java 8 classes reads as 52");

            // a nested or synthetic class follows its outermost class: two compilations do not make the same ones
            Path ship = dir.resolve("ship");
            write(ship.resolve("a/B.class"), "x");
            that(PureShipTask.makesWay(ship, "a/B.class"), "downgrade:a class ship supplies makes way");
            that(PureShipTask.makesWay(ship, "a/B$1.class"), "downgrade:a synthetic class of a class ship supplies makes way (its other compilation has none)");
            that(PureShipTask.makesWay(ship, "a/B$Inner$1.class"), "downgrade:so does a class nested twice");
            that(!PureShipTask.makesWay(ship, "a/C$1.class"), "downgrade:a nested class of a class ship does not supply stays");
            that(!PureShipTask.makesWay(ship, "a/Bee.class"), "downgrade:a name that merely starts like a shipped class stays");
            that(!PureShipTask.makesWay(ship, "a/B.txt"), "downgrade:only classes make way");

            // a backport that throws is the artifact's problem only when the artifact calls it
            Path called = dir.resolve("called.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(called))) {
                entry(z, "pfx/J_Q_Thing.class", throwing("pfx/J_Q_Thing", "onExit", "no backport"));
                entry(z, "own/Caller.class", calling("own/Caller", "pfx/J_Q_Thing", "onExit"));
            }
            that(Linkage.missingStubs(called, java.util.List.of("pfx", "pfx_ports"), false).stream().anyMatch(l -> l.startsWith("own/Caller#run -> ")),
                    "downgrade:a call of a backport that throws is named");
            Path unreached = dir.resolve("unreached.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(unreached))) {
                entry(z, "pfx/J_Q_Thing.class", throwing("pfx/J_Q_Thing", "onExit", "no backport"));
                entry(z, "own/Other.class", calling("own/Other", "java/lang/System", "gc"));
            }
            that(Linkage.missingStubs(unreached, "pfx", false).isEmpty(),
                    "downgrade:a partial backport nothing calls does not fail a jar of the game's own size");
            that(!Linkage.missingStubs(unreached, "pfx", true).isEmpty(),
                    "downgrade:the era gate still names it: a bundle small enough to have no such corner");

            // a member named on a class of the jar that only a class outside it declares is where the era must have it
            Path inherited = dir.resolve("inherited.jar");
            try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(Files.newOutputStream(inherited))) {
                entry(z, "own/Loader.class", extending("own/Loader", "java/lang/ClassLoader", "declared"));
                entry(z, "own/Use.class", callingVirtual("own/Use", "own/Loader", "getDefinedPackage", "(Ljava/lang/String;)Ljava/lang/Package;"));
                entry(z, "own/Use2.class", callingVirtual("own/Use2", "own/Loader", "declared", "()V"));
            }
            java.util.Map<String, String> refs = Linkage.outsideReferences(inherited);
            that(refs.containsKey("M java/lang/ClassLoader getDefinedPackage (Ljava/lang/String;)Ljava/lang/Package;"),
                    "downgrade:a method the jar's class inherits from a class outside is traced to it (Java 9's getDefinedPackage on Java 8)");
            that(refs.keySet().stream().noneMatch(r -> r.contains(" declared ")), "downgrade:a method the jar's class declares is not");
        } finally {
            delete(dir);
        }
    }

    /** A class extending {@code superName} that declares one static method {@code declared()V}. */
    private static byte[] extending(String owner, String superName, String declared) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, superName, null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, declared, "()V", null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose {@code run} calls {@code target.method(desc)} on a null receiver (never run; only read). */
    private static byte[] callingVirtual(String owner, String target, String method, String desc) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.CHECKCAST, target);
        if (desc.startsWith("(Ljava/lang/String;)")) {
            mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        }
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, target, method, desc, false);
        if (!desc.endsWith(")V")) {
            mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        }
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] header(int major) {
        return new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 0, 0, (byte) (major >> 8), (byte) major};
    }

    private static void entry(java.util.zip.ZipOutputStream z, String name, byte[] bytes) throws IOException {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(bytes);
        z.closeEntry();
    }

    /** A class whose static method {@code name} throws {@code MissingStubError(what)}, as JvmDowngrader writes one. */
    private static byte[] throwing(String owner, String name, String what) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                name, "()V", null, null);
        mv.visitCode();
        mv.visitTypeInsn(org.objectweb.asm.Opcodes.NEW, "xyz/wagyourtail/jvmdg/exc/MissingStubError");
        mv.visitInsn(org.objectweb.asm.Opcodes.DUP);
        mv.visitLdcInsn(what);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "xyz/wagyourtail/jvmdg/exc/MissingStubError", "<init>",
                "(Ljava/lang/String;)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.ATHROW);
        mv.visitMaxs(3, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose static {@code run} calls {@code target.method()} (static, no arguments). */
    private static byte[] calling(String owner, String target, String method) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "run", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, target, method, "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    // --- the portability surface (docs/decisions/0006) ----------------------------------------------------------------

    static void surface() {
        that(SurfaceTask.count("net.minecraft.World w;", "net.minecraft") == 1, "surface:one reference counts once");
        that(SurfaceTask.count("net.minecraft.World a; net.minecraft.World b;", "net.minecraft") == 2,
                "surface:the same reference twice counts twice (a textual count, not a distinct-type count)");
        that(SurfaceTask.count("import net.minecraft.server.MinecraftServer;", "net.minecraft") == 1,
                "surface:a class in a package under the root counts");
        that(SurfaceTask.count("net.minecraftforge.Something s;", "net.minecraft") == 0,
                "surface:a package that merely starts with the platform root's name is not a reference to it");
        // count() itself takes already-stripped code, exactly as SurfaceTask.run() strips it: comments out
        // (IsolationTask.code()), then string literal contents out too (PurityTask.STRING) — once, not by count() on
        // every call.
        that(SurfaceTask.count(strip("// net.minecraft.World w;\n/* net.minecraft.World */"), "net.minecraft") == 0,
                "surface:a mention only in a comment is not a reference");
        that(SurfaceTask.count(strip("String s = \"net.minecraft.World\";"), "net.minecraft") == 0,
                "surface:a mention only in a string literal is not a reference");
        that("com.example.mymod.core".equals(SurfaceTask.packageOf("package com.example.mymod.core;\nclass X {}")),
                "surface:the package a file declares is read the same way everywhere else in the plugin reads it");
    }

    /** Exactly how {@link SurfaceTask#run()} strips a file before counting: comments, then string literal contents. */
    static String strip(String text) {
        return PurityTask.STRING.matcher(IsolationTask.code(text)).replaceAll("\"\"");
    }

    // --- the repository check, on broken copies -----------------------------------------------------------------------

    static final Set<String> SKIP = Set.of("build", "out", ".gradle", ".git", ".jvmdg", "archive");

    static void repository(Path root) throws IOException {
        Path base = copy(root);
        Set<String> baseline = findings(base);
        expect(root, "an unregistered package on disk", r -> mkdirs(r.resolve("contracts/zzz-unregistered/src/mycelium/zzz/v1/model")),
                f -> f.contains("zzz-unregistered") && f.contains("no row"), baseline);
        expect(root, "a registry row with no package", r -> rename(r.resolve("contracts/cue"), r.resolve("contracts/cue-moved")),
                f -> f.contains("presentation.particle-cue@1") && f.contains("not a package on disk"), baseline);
        expect(root, "a transaction copy that drifted", r -> replace(r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java"),
                        "undos.clear();", "undos.clear(); undos.clear();"),
                f -> f.contains("cue/v1/model/Tx.java") && f.contains("transaction"), baseline);
        expect(root, "only the class's name and comments changed", r -> {
                    Path tx = r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java");
                    replace(tx, "public final class Tx", "/** renamed */ public final class Tx");
                },
                f -> f.contains("cue/v1/model/Tx.java"), baseline, false);
        expect(root, "an era bound that names no real version", r -> replace(r.resolve("eras/minecraft.json"), "\"to\": \"b1.x\"", "\"to\": \"b1.9\""),
                f -> f.contains("'b1.9'") && f.contains("not a version the launcher names"), baseline);
        expect(root, "an era naming a loader with no packages", r -> replace(r.resolve("eras/minecraft.json"),
                        "\"babric\",", "\"babric\", \"riftloader\","),
                f -> f.contains("riftloader"), baseline);
        expect(root, "a gap in the era table", r -> replace(r.resolve("eras/minecraft.json"), "\"from\": \"1.18\"", "\"from\": \"1.19\""),
                f -> f.contains("1.18") && f.contains("no era"), baseline);
        expect(root, "a wrong count in README's status line", r -> replaceRegex(r.resolve("README.md"), "(\\| Status \\|[^\\n]*?)\\d+ packages", "$1999 packages"),
                f -> f.contains("999 packages"), baseline);
        expect(root, "a count stated outside the status line", r -> append(r.resolve("AGENTS.md"), "\nThere are twelve contract packages.\n"),
                f -> f.contains("AGENTS.md") && f.contains("twelve contract packages"), baseline);
        expect(root, "a path that does not exist", r -> append(r.resolve("CONTRACT-PACKAGE.md"), "\nSee `harness/src/mycelium/harness/Nowhere.java`.\n"),
                f -> f.contains("Nowhere.java"), baseline);
        expect(root, "a package keeping its own harness", r -> write(r.resolve("contracts/cue/src/mycelium/cue/v1/harness/Check.java"),
                        "package mycelium.cue.v1.harness; public final class Check {}"),
                f -> f.contains("cue") && f.contains("own Check.java"), baseline);
        expect(root, "a package whose harness did not report", r -> UNREPORTED.add("presentation.particle-cue@1"),
                f -> f.contains("presentation.particle-cue@1") && f.contains("no harness report"), baseline);
        expect(root, "a path that leaves the repository", r -> append(r.resolve("AGENTS.md"), "\nSee `../elsewhere/AGENTS.md`.\n"),
                f -> f.contains("../elsewhere/AGENTS.md") && f.contains("outside this repository"), baseline);
        expect(root, "a contract document naming a file its package lacks", r -> append(r.resolve("contracts/cue/SPEC.md"),
                        "\nSee `harness/Nowhere.java`.\n"),
                f -> f.contains("contracts/cue/SPEC.md") && f.contains("harness/Nowhere.java"), baseline);
        expect(root, "a contract document naming a file its package has", r -> append(r.resolve("contracts/cue/SPEC.md"),
                        "\nSee `harness/Run.java` and `model/Tx.java`.\n"),
                f -> f.contains("contracts/cue/SPEC.md"), baseline, false);
        expect(root, "a bare file name from a consumer's project", r -> append(r.resolve("CONTRACT-PACKAGE.md"),
                        "\nA consumer's `fabric.mod.json` is its own.\n"),
                f -> f.contains("fabric.mod.json"), baseline, false);
        expect(root, "an era that runs backwards", r -> replaceRegex(r.resolve("eras/minecraft.json"),
                        "\"from\": \"1\\.20\\.5\",(\\s+)\"to\": \"1\\.21\\.x\"", "\"from\": \"1.21.4\",$1\"to\": \"1.20.6\""),
                f -> f.contains("runs backwards"), baseline);
        expect(root, "a model reaching another contract", r -> append(r.resolve("contracts/cue/src/mycelium/cue/v1/model/Tx.java"),
                        "\nclass Reach { mycelium.flow.v1.model.Receipt r; }\n"),
                f -> f.contains("reaches mycelium.flow.v1"), baseline);
        expect(root, "a plugin class the map does not name", r -> write(r.resolve("gradle-plugin/src/main/java/dev/woflo/mycelium/gradle/Unmapped.java"),
                        "package dev.woflo.mycelium.gradle; final class Unmapped {}"),
                f -> f.contains("Unmapped.java"), baseline);
        delete(base);
    }

    interface Break {
        void apply(Path copy) throws IOException;
    }

    static void expect(Path root, String what, Break change, Predicate<String> finding, Set<String> baseline) throws IOException {
        expect(root, what, change, finding, baseline, true);
    }

    static void expect(Path root, String what, Break change, Predicate<String> finding, Set<String> baseline, boolean fires)
            throws IOException {
        Path copy = copy(root);
        try {
            change.apply(copy);
            List<String> fresh = new ArrayList<>();
            for (String f : findings(copy)) {
                if (!baseline.contains(f)) {
                    fresh.add(f);
                }
            }
            boolean fired = fresh.stream().anyMatch(finding);
            that(fired == fires, "repository:" + what + (fires ? " is found" : " is not a finding") + " (new findings: " + fresh + ")");
        } finally {
            UNREPORTED.clear();
            delete(copy);
        }
    }

    /** Contracts whose harness report the next check is not handed (a break of what ran, not of a file). */
    static final Set<String> UNREPORTED = new java.util.HashSet<>();

    /** What myceliumVerify would hand the check: a report for every registered contract, but those {@link #UNREPORTED}. */
    static java.util.Map<String, Integer> reported(Path copy) {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        try {
            for (ContractRegistry.Entry e : ContractRegistry.load(copy.toFile()).all()) {
                if (!UNREPORTED.contains(e.id())) {
                    out.put(e.id(), 1);
                }
            }
        } catch (RuntimeException e) {
            // a registry that does not load is the check's own finding
        }
        return out;
    }

    static Set<String> findings(Path copy) throws IOException {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (RepositoryCheck.Finding f : RepositoryCheck.run(copy, reported(copy)).findings()) {
            out.add(f.toString().replace(copy.toString().replace('\\', '/'), "<root>"));
        }
        return out;
    }

    static Path copy(Path root) throws IOException {
        Path to = Files.createTempDirectory("mycelium-selftest");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path rel = root.relativize(p);
                boolean skip = false;
                for (Path part : rel) {
                    if (SKIP.contains(part.toString())) {
                        skip = true;
                        break;
                    }
                }
                if (skip) {
                    continue;
                }
                Path target = to.resolve(rel.toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
        return to;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    static void mkdirs(Path p) throws IOException {
        Files.createDirectories(p);
    }

    static void rename(Path from, Path to) throws IOException {
        Files.move(from, to);
    }

    static void write(Path p, String text) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    static void append(Path p, String text) throws IOException {
        Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8) + text, StandardCharsets.UTF_8);
    }

    static void replace(Path p, String from, String to) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        if (!s.contains(from)) {
            throw new IllegalStateException("self-test setup: '" + from + "' not in " + p);
        }
        Files.writeString(p, s.replace(from, to), StandardCharsets.UTF_8);
    }

    static void replaceRegex(Path p, String regex, String to) throws IOException {
        String s = Files.readString(p, StandardCharsets.UTF_8);
        String t = s.replaceFirst(regex, to);
        if (t.equals(s)) {
            throw new IllegalStateException("self-test setup: /" + regex + "/ not in " + p);
        }
        Files.writeString(p, t, StandardCharsets.UTF_8);
    }
}
