package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static dev.woflo.mycelium.gradle.SelfTest.that;

/**
 * Self-tests for decision 0016, bridges: the variant and overlays a version draws, the sources laid out under a mod's
 * namespace, an override, a skip, the conformance wiring, the override check, the repository check shown to fire on each
 * way a bridge can be broken, and the linkage gate shown to refuse a planted bad reference on a game built here (nothing
 * reads the network).
 */
final class BridgeSelfTest {
    private BridgeSelfTest() {
    }

    static void run(Path root) throws Exception {
        selection(root);
        layout(root);
        importTask(root);
        overrideCheck();
        Path tmp = Files.createTempDirectory("mycelium-bridges-");
        try {
            wiring(root, tmp);
            linkage(tmp);
            gameClasspath(tmp);
        } finally {
            SelfTest.delete(tmp);
        }
        repository(root);
    }

    private static Bridges.Bridge carrier(Path root) throws IOException {
        return Bridges.require(root, "item-carrier");
    }

    // --- which variant and overlays a version draws ------------------------------------------------------------------------

    static void selection(Path root) throws IOException {
        Eras eras = Eras.load(root.toFile());
        Bridges.Bridge b = carrier(root);
        String[][] expect = {
                {"26.3", "custom-data + 1.21.5"}, {"26.1", "custom-data + 1.21.5"}, {"1.21.11", "custom-data + 1.21.5"},
                {"1.21.5", "custom-data + 1.21.5"}, {"1.21.4", "custom-data"}, {"1.21.1", "custom-data"}, {"1.20.5", "custom-data"},
                {"1.20.4", "root-tag"}, {"1.20.1", "root-tag"}, {"1.15", "root-tag"}, {"1.14.4", "root-tag + 1.14"}, {"1.14", "root-tag + 1.14"},
                {"1.13.2", "none"}, {"1.12.2", "none"}, {"b1.7.3", "none"}};
        for (String[] e : expect) {
            Bridges.Selection s = Bridges.select(b, eras, e[0]);
            that(s.describe().equals(e[1]), "bridges:Minecraft " + e[0] + " draws " + e[1] + ", not " + s.describe());
        }
        // world-clock: the native variant from 26.1 (with its overlay for 26.1 and 26.2), the emulated one from 1.14 (with an overlay wherever
        // the saved data, the rule or the tick rate manager changes shape), none before the game's names
        Bridges.Bridge clock = Bridges.require(root, "world-clock");
        String[][] clockExpect = {
                {"26.3", "native-clock"}, {"26.2", "native-clock + 26.1"}, {"26.1", "native-clock + 26.1"}, {"1.21.11", "emulated-clock + 1.21.11"},
                {"1.21.10", "emulated-clock + 1.21.5"}, {"1.21.5", "emulated-clock + 1.21.5"}, {"1.21.4", "emulated-clock"}, {"1.21.1", "emulated-clock"},
                {"1.20.5", "emulated-clock"}, {"1.20.4", "emulated-clock + 1.20.3"}, {"1.20.3", "emulated-clock + 1.20.3"}, {"1.20.2", "emulated-clock + 1.20.2"},
                {"1.20.1", "emulated-clock + 1.17"}, {"1.17", "emulated-clock + 1.17"}, {"1.16.5", "emulated-clock + 1.16"}, {"1.16", "emulated-clock + 1.16"},
                {"1.15.2", "emulated-clock + 1.14"}, {"1.14", "emulated-clock + 1.14"}, {"1.13.2", "none"}, {"b1.7.3", "none"}};
        for (String[] e : clockExpect) {
            Bridges.Selection s = Bridges.select(clock, eras, e[0]);
            that(s.describe().equals(e[1]), "bridges:world-clock on Minecraft " + e[0] + " draws " + e[1] + ", not " + s.describe());
        }
        // item-presentation: the item definition from 1.21.4, the model with overrides before it (the number in the root tag, then, from
        // 1.20.5, in a component), none before the game's names
        Bridges.Bridge presenter = Bridges.require(root, "item-presentation");
        String[][] presenterExpect = {
                {"26.3", "item-definition"}, {"26.1", "item-definition"}, {"1.21.11", "item-definition"}, {"1.21.4", "item-definition"},
                {"1.21.3", "model-overrides + 1.20.5"}, {"1.21.1", "model-overrides + 1.20.5"}, {"1.20.5", "model-overrides + 1.20.5"},
                {"1.20.4", "model-overrides"}, {"1.20.1", "model-overrides"}, {"1.14", "model-overrides"}, {"1.13.2", "none"}, {"b1.7.3", "none"}};
        for (String[] e : presenterExpect) {
            Bridges.Selection s = Bridges.select(presenter, eras, e[0]);
            that(s.describe().equals(e[1]), "bridges:item-presentation on Minecraft " + e[0] + " draws " + e[1] + ", not " + s.describe());
        }
        that(Bridges.conformanceFiles(Bridges.select(presenter, eras, "1.21.1")).get("mycelium/bridges/item_presentation/conformance/Wire.java").toString()
                        .replace('\\', '/').contains("overlays/1.20.5/conformance")
                        && Bridges.conformanceFiles(Bridges.select(presenter, eras, "1.20.1")).get("mycelium/bridges/item_presentation/conformance/Wire.java").toString()
                        .replace('\\', '/').contains("model-overrides/conformance")
                        && Bridges.conformanceFiles(Bridges.select(presenter, eras, "1.21.4")).containsKey("mycelium/bridges/item_presentation/conformance/PresenterConformance.java"),
                "bridges:item-presentation's cases are the bridge's, and what they need of the stack is the variant's or its overlay's");
        that(Bridges.conformanceResourceFiles(Bridges.select(presenter, eras, "26.3")).isEmpty(), "bridges:item-presentation's cases need no data files");
        // a jar entry of a bridge's conformance cases is recognised by where the import put it, and nothing else is
        String prefix = "com/example/mod/mycelium/bridges/";
        that(MyceliumPlugin.isConformanceClass(prefix + "item_carrier/conformance/Wire.class", prefix)
                        && MyceliumPlugin.isConformanceClass(prefix + "world_clock/conformance/ClockConformance$Case.class", prefix)
                        && !MyceliumPlugin.isConformanceClass(prefix + "item_carrier/ItemCarrier.class", prefix)
                        && !MyceliumPlugin.isConformanceClass(prefix + "item_carrier/conformance/Wire.java", prefix)
                        && !MyceliumPlugin.isConformanceClass("com/example/mod/Conformance.class", prefix)
                        && !MyceliumPlugin.isConformanceClass("com/example/mod/conformance/Wire.class", prefix),
                "bridges:a conformance class is one under <namespace>/bridges/<slug>/conformance/, and nothing else is");
        Map<String, Path> data = Bridges.conformanceResourceFiles(Bridges.select(clock, eras, "26.3"));
        that(data.keySet().equals(java.util.Set.of("data/mycelium_conformance/world_clock/calendar.json", "data/mycelium_conformance/timeline/calendar.json"))
                        && Bridges.conformanceResourceFiles(Bridges.select(clock, eras, "1.21.1")).isEmpty()
                        && Bridges.conformanceResourceFiles(Bridges.select(clock, eras, "1.13.2")).isEmpty(),
                "bridges:a variant's conformance data (a datapack clock on 26.1 and later) is its own, and a version without it has none: " + data.keySet());
        Bridges.Selection none = Bridges.select(b, eras, "1.13.2");
        that(!none.maps() && none.why().contains("Mojang") && Bridges.sourceFiles(none).isEmpty() && Bridges.conformanceFiles(none).isEmpty(),
                "bridges:a version the game cannot do it on maps nowhere and says why: " + none.why());
        that(SelfTest.refuses(() -> Bridges.select(b, Eras.parse("{\"schema\":2,\"eras\":[]}", "t"), "1.21.1"), "no item.carrier row covers"),
                "bridges:a table without rows answers no version, by name");
        that(SelfTest.refuses(() -> Bridges.select(b, Eras.parse("{\"schema\":2,\"eras\":[],\"realizations\":{\"item.carrier\":["
                        + "{\"from\":\"1.0\",\"to\":\"26.x\",\"realization\":\"magic\"}]}}", "t"), "1.21.1"), "has no variant for"),
                "bridges:a realization the bridge has no variant for is refused, by name");
        Map<String, Path> modern = Bridges.sourceFiles(Bridges.select(b, eras, "1.21.11"));
        Map<String, Path> older = Bridges.sourceFiles(Bridges.select(b, eras, "1.21.1"));
        that(modern.keySet().equals(older.keySet()) && !modern.get("mycelium/bridges/item_carrier/Nbt.java").equals(older.get("mycelium/bridges/item_carrier/Nbt.java"))
                        && modern.get("mycelium/bridges/item_carrier/ItemCarrier.java").equals(older.get("mycelium/bridges/item_carrier/ItemCarrier.java")),
                "bridges:an overlay replaces the same-named file of its variant and leaves the others");
        that(Bridges.conformanceFiles(Bridges.select(b, eras, "1.21.11")).containsKey("mycelium/bridges/item_carrier/conformance/ItemCarrierConformance.java")
                        && Bridges.conformanceFiles(Bridges.select(b, eras, "1.21.11")).get("mycelium/bridges/item_carrier/conformance/Wire.java").toString().replace('\\', '/').contains("overlays/1.21.5/conformance"),
                "bridges:the conformance cases are the bridge's, and what they need of a variant is the variant's (or its overlay's)");
    }

    // --- laid out under a mod's namespace ------------------------------------------------------------------------------------

    static void layout(Path root) throws IOException {
        Eras eras = Eras.load(root.toFile());
        Path out = Files.createTempDirectory("mycelium-bridge-layout-");
        try {
            Path src = out.resolve("src");
            Path conformance = out.resolve("conformance");
            List<String> written = Bridges.materialize(Bridges.select(carrier(root), eras, "1.21.11"), "com.example.mod.mycelium", src, conformance);
            Path nbt = src.resolve("com/example/mod/mycelium/bridges/item_carrier/Nbt.java");
            String text = Files.readString(nbt);
            that(written.contains("com/example/mod/mycelium/bridges/item_carrier/ItemCarrier.java") && Files.isRegularFile(nbt),
                    "bridges:the sources land under the mod's namespace: " + written);
            that(text.contains("package com.example.mod.mycelium.bridges.item_carrier;")
                            && text.contains("import com.example.mod.mycelium.item_component.v1.model.TagNode;") && !text.contains("import mycelium."),
                    "bridges:the package and the contract's model are rewritten to the mod's own copy, as a model's imports are");
            that(text.contains("c.keySet()") && !text.contains("getAllKeys"), "bridges:the overlay's accessors are what a 1.21.11 mod gets");
            that(Files.isRegularFile(conformance.resolve("com/example/mod/mycelium/bridges/item_carrier/conformance/ItemCarrierConformance.java"))
                            && Files.readString(conformance.resolve("com/example/mod/mycelium/bridges/item_carrier/conformance/Wire.java")).contains("value()"),
                    "bridges:the conformance cases are laid out apart, with the overlay's rig");
            Path clocks = out.resolve("clocks");
            List<String> clockWritten = Bridges.materialize(Bridges.select(Bridges.require(root, "world-clock"), eras, "26.3"), "com.example.mod.mycelium",
                    clocks.resolve("src"), clocks.resolve("conformance"), clocks.resolve("resources"));
            that(Files.isRegularFile(clocks.resolve("resources/data/mycelium_conformance/world_clock/calendar.json"))
                            && Files.isRegularFile(clocks.resolve("resources/data/mycelium_conformance/timeline/calendar.json"))
                            && clockWritten.contains("conformance resource: data/mycelium_conformance/timeline/calendar.json")
                            && Files.readString(clocks.resolve("resources/data/mycelium_conformance/timeline/calendar.json")).contains("\"period_ticks\": 7000"),
                    "bridges:conformance data is laid out as it is, beside the cases, only when the cases are asked for: " + clockWritten);
            Bridges.materialize(Bridges.select(Bridges.require(root, "world-clock"), eras, "26.3"), "x.y", clocks.resolve("bare/src"), null, clocks.resolve("bare/resources"));
            that(!Files.exists(clocks.resolve("bare/resources")), "bridges:no conformance, no conformance data");
            Path older = out.resolve("older");
            Bridges.materialize(Bridges.select(carrier(root), eras, "1.20.1"), "com.example.mod.mycelium", older.resolve("src"), null);
            String root1201 = Files.readString(older.resolve("src/com/example/mod/mycelium/bridges/item_carrier/ItemCarrier.java"));
            that(root1201.contains("stack.getTag()") && !root1201.contains("CustomData") && !Files.exists(older.resolve("conformance")),
                    "bridges:1.20.1 draws the root-tag variant, and no conformance unless asked");
            Path none = out.resolve("none");
            that(Bridges.materialize(Bridges.select(carrier(root), eras, "1.12.2"), "x.y", none.resolve("src"), none.resolve("c")).isEmpty(),
                    "bridges:a version the bridge cannot map on writes nothing");
        } finally {
            SelfTest.delete(out);
        }
        // a bridge that reaches the harness is refused, as a model is
        Path scratch = SelfTest.copy(root);
        try {
            SelfTest.append(scratch.resolve("bridges/item-carrier/custom-data/src/mycelium/bridges/item_carrier/ItemCarrier.java"), "// mycelium.harness.Check\n");
            Path out2 = Files.createTempDirectory("mycelium-bridge-harness-");
            try {
                that(SelfTest.refuses(() -> {
                    try {
                        Bridges.materialize(Bridges.select(Bridges.require(scratch, "item-carrier"), Eras.load(scratch.toFile()), "1.21.1"), "x.y", out2, null);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                }, "must not depend on the harness"), "bridges:a bridge that reaches the harness is refused");
            } finally {
                SelfTest.delete(out2);
            }
        } finally {
            SelfTest.delete(scratch);
        }
    }

    // --- the import: use, skip, override, conformance --------------------------------------------------------------------------

    private static JsonObject imported(Path root, List<String> bridges, String minecraft, List<String> skip,
                                       Map<String, String> overrides, boolean conformance, Path out) throws IOException {
        SelfTest.delete(out); // as the task cleans its outputs
        return ImportBridgesTask.importInto(root, bridges, "com.example.mod.mycelium", minecraft, skip, overrides, conformance,
                out.resolve("src"), out.resolve("conformance"), out.resolve("resources"), (message, args) -> { });
    }

    private static JsonObject bridge(JsonObject report, String slug) {
        for (JsonElement e : report.getAsJsonArray("bridges")) {
            if (e.getAsJsonObject().get("slug").getAsString().equals(slug)) {
                return e.getAsJsonObject();
            }
        }
        return null;
    }

    static void importTask(Path root) throws IOException {
        Path out = Files.createTempDirectory("mycelium-bridge-import-");
        List<String> carrier = List.of("item-carrier");
        try {
            JsonObject taken = imported(root, carrier, "1.21.1", List.of(), Map.of(), false, out);
            JsonObject one = bridge(taken, "item-carrier");
            that(one != null && one.get("status").getAsString().equals("variant") && one.get("variant").getAsString().equals("custom-data")
                            && one.getAsJsonArray("overlays").isEmpty() && !one.get("conformance").getAsBoolean(),
                    "bridges:a bridge the mod takes draws the variant its version draws: " + taken);
            that(Files.isRegularFile(out.resolve("src/com/example/mod/mycelium/bridges/item_carrier/ItemCarrier.java"))
                            && !Files.exists(out.resolve("conformance/com")), "bridges:the sources are written and the conformance cases are not, unless asked");

            JsonObject withConformance = imported(root, carrier, "26.3", List.of(), Map.of(), true, out);
            JsonObject modern = bridge(withConformance, "item-carrier");
            that(modern.getAsJsonArray("overlays").size() == 1 && modern.getAsJsonArray("overlays").get(0).getAsString().equals("1.21.5")
                            && modern.get("conformance").getAsBoolean()
                            && Files.isRegularFile(out.resolve("conformance/com/example/mod/mycelium/bridges/item_carrier/conformance/ItemCarrierConformance.java")),
                    "bridges:conformance() adds the cases, and the record names the variant and overlays: " + modern);

            JsonObject skipped = imported(root, carrier, "1.21.1", List.of("item-carrier"), Map.of(), false, out);
            that(bridge(skipped, "item-carrier").get("status").getAsString().equals("skipped") && !Files.exists(out.resolve("src/com")),
                    "bridges:a skipped bridge imports nothing and the record says it was skipped");

            JsonObject unused = imported(root, List.of(), "1.21.1", List.of(), Map.of(), false, out);
            that(unused.getAsJsonArray("bridges").isEmpty() && !Files.exists(out.resolve("src/com")),
                    "bridges:a mod that takes no bridge gets none: importing a contract's model brings no bridge");
            JsonObject used = imported(root, carrier, "1.20.1", List.of(), Map.of(), false, out);
            that(bridge(used, "item-carrier").get("variant").getAsString().equals("root-tag"),
                    "bridges:the target version still chooses the variant");

            JsonObject cannot = imported(root, carrier, "1.12.2", List.of(), Map.of(), false, out);
            that(bridge(cannot, "item-carrier").get("status").getAsString().equals("none") && bridge(cannot, "item-carrier").get("why").getAsString().contains("Mojang"),
                    "bridges:a version the bridge cannot map on is recorded as none, with why: " + cannot);

            // no version to choose by, and a version no row of the table covers, are the same finding (B2), not a build that cannot run
            JsonObject unset = imported(root, carrier, null, List.of(), Map.of(), false, out);
            that(bridge(unset, "item-carrier").get("status").getAsString().equals("none") && bridge(unset, "item-carrier").get("why").getAsString().contains("is not set")
                            && !unset.has("minecraft"),
                    "bridges:a build with no target version records its bridges as none (B2), it does not throw: " + unset);
            Path scratch = Files.createTempDirectory("mycelium-bridge-norow-");
            try {
                SelfTest.copyTree(root.resolve("bridges"), scratch.resolve("bridges"));
                SelfTest.copyTree(root.resolve("eras"), scratch.resolve("eras"));
                SelfTest.replace(scratch.resolve("eras/minecraft.json"), "\"item.carrier\": [", "\"item.carrier.renamed\": [");
                JsonObject norow = imported(scratch, carrier, "1.21.1", List.of(), Map.of(), false, out);
                that(bridge(norow, "item-carrier").get("status").getAsString().equals("none")
                                && bridge(norow, "item-carrier").get("why").getAsString().contains("has no item.carrier row covering Minecraft 1.21.1"),
                        "bridges:a target the realization table has no row for is recorded as none (B2), as the docs say: " + norow);
            } finally {
                SelfTest.delete(scratch);
            }

            // an override: no source of the bridge's, and the cases that need none of it, so the class is held to the contract's laws
            JsonObject overridden = imported(root, carrier, "1.21.1", List.of(), Map.of("item-carrier", "com.example.MyCarrier"), true, out);
            JsonObject o = bridge(overridden, "item-carrier");
            that(o.get("status").getAsString().equals("override") && o.get("class").getAsString().equals("com.example.MyCarrier") && o.get("conformance").getAsBoolean()
                            && !Files.exists(out.resolve("src/com"))
                            && Files.isRegularFile(out.resolve("conformance/com/example/mod/mycelium/bridges/item_carrier/conformance/ItemCarrierConformance.java"))
                            && Files.isRegularFile(out.resolve("conformance/com/example/mod/mycelium/bridges/item_carrier/conformance/Wire.java")),
                    "bridges:an override drops the bridge's sources, is recorded with its class, and with conformance() gets the cases and the game's rig: " + o);
            JsonObject overriddenQuiet = imported(root, carrier, "1.21.1", List.of(), Map.of("item-carrier", "com.example.MyCarrier"), false, out);
            that(!bridge(overriddenQuiet, "item-carrier").get("conformance").getAsBoolean() && !Files.exists(out.resolve("conformance/com")),
                    "bridges:an override gets the conformance cases only when the mod asks for them");
            // ... and for the world clock, whose cases once reached the bridge's own port through the rig
            JsonObject clockOverride = imported(root, List.of("world-clock"), "26.3", List.of(), Map.of("world-clock", "com.example.MyClocks"), true, out);
            Path clockCases = out.resolve("conformance/com/example/mod/mycelium/bridges/world_clock/conformance");
            that(bridge(clockOverride, "world-clock").get("conformance").getAsBoolean() && Files.isRegularFile(clockCases.resolve("ClockCases.java"))
                            && Files.isRegularFile(clockCases.resolve("Wire.java")) && !Files.exists(clockCases.resolve("ClockConformance.java"))
                            && !Files.exists(out.resolve("src/com")) && Files.isRegularFile(out.resolve("resources/data/mycelium_conformance/world_clock/calendar.json")),
                    "bridges:an override of the clock gets ClockCases, the game's rig and the data the cases use, not the bridge's entry point: " + clockOverride);
            try (var walk = Files.walk(out.resolve("conformance"))) {
                for (Path file : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String text = Files.readString(file);
                    that(!java.util.regex.Pattern.compile("com\\.example\\.mod\\.mycelium\\.bridges\\.world_clock\\.(?!conformance\\b)[A-Z]").matcher(text).find(),
                            "bridges:what an override imports names no class of the bridge it replaces: " + out.relativize(file));
                }
            }
            JsonObject clockTaken = imported(root, List.of("world-clock"), "26.3", List.of(), Map.of(), true, out);
            that(bridge(clockTaken, "world-clock").get("conformance").getAsBoolean() && Files.isRegularFile(clockCases.resolve("ClockConformance.java"))
                            && Files.isRegularFile(clockCases.resolve("ClockCases.java")) && Files.isRegularFile(out.resolve("src/com/example/mod/mycelium/bridges/world_clock/WorldClocks.java")),
                    "bridges:a mod that takes the clock gets ClockConformance (the bridge's own entry point) as well as ClockCases");
            JsonObject overrideNoVersion = imported(root, carrier, "1.12.2", List.of(), Map.of("item-carrier", "x.Y"), true, out);
            that(bridge(overrideNoVersion, "item-carrier").get("status").getAsString().equals("override") && !bridge(overrideNoVersion, "item-carrier").get("conformance").getAsBoolean()
                            && bridge(overrideNoVersion, "item-carrier").has("conformanceWhy"),
                    "bridges:an override on a version with no realization has no rig of the game to run cases on, and says so: " + overrideNoVersion);

            that(refusesGradle(() -> imported(root, carrier, "1.21.1", List.of("item-carrier"), Map.of("item-carrier", "x.Y"), false, out),
                            "names a bridge this project skips"), "bridges:an override of a bridge the mod skips is refused, by name");
            that(refusesGradle(() -> imported(root, List.of("ghost"), "1.21.1", List.of(), Map.of(), false, out), "no bridge 'ghost'"),
                    "bridges:a bridge that does not exist is named, with the ones that do");
            that(refusesGradle(() -> imported(root, carrier, "1.21.1", List.of("ghost"), Map.of(), false, out), "skip(\"ghost\") names no bridge"),
                    "bridges:skip(...) of a bridge that does not exist is refused, as use(...) is");
            JsonObject overrideNeedsNoVersion = imported(root, List.of(), null, List.of(), Map.of("item-carrier", "x.Y"), false, out);
            that(bridge(overrideNeedsNoVersion, "item-carrier").get("status").getAsString().equals("override"),
                    "bridges:an override takes its bridge, and needs no target version, since nothing is chosen");
        } finally {
            SelfTest.delete(out);
        }
    }

    private static boolean refusesGradle(Fetch f, String fragment) {
        try {
            f.run();
            return false;
        } catch (GradleException e) {
            return e.getMessage().contains(fragment);
        } catch (IOException e) {
            return false;
        }
    }

    private interface Fetch {
        void run() throws IOException;
    }

    // --- an override is held to the port ---------------------------------------------------------------------------------------

    private static byte[] clazz(String name, String superName, String... interfaces) {
        ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, superName, interfaces);
        w.visitEnd();
        return w.toByteArray();
    }

    static void overrideCheck() throws IOException {
        String port = "com/example/mod/mycelium/item_component/v1/model/StackCarrier";
        Path classes = Files.createTempDirectory("mycelium-bridge-classes-");
        try {
            Map<String, byte[]> files = Map.of(
                    "com/example/Direct", clazz("com/example/Direct", "java/lang/Object", port),
                    "com/example/Base", clazz("com/example/Base", "java/lang/Object", port),
                    "com/example/Sub", clazz("com/example/Sub", "com/example/Base"),
                    "com/example/Plain", clazz("com/example/Plain", "java/lang/Object", "java/lang/Runnable"),
                    "com/example/Outside", clazz("com/example/Outside", "net/minecraft/world/Thing"));
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                Path p = classes.resolve(e.getKey() + ".class");
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue());
            }
            Map<String, BridgeCheckTask.Hierarchy> h = BridgeCheckTask.hierarchy(Set.of(classes.toFile()));
            that(BridgeCheckTask.implementsPort(h, "com/example/Direct", port) == BridgeCheckTask.Verdict.YES, "bridges:a class that implements the port is one");
            that(BridgeCheckTask.implementsPort(h, "com/example/Sub", port) == BridgeCheckTask.Verdict.YES, "bridges:and so is one that extends such a class");
            that(BridgeCheckTask.implementsPort(h, "com/example/Plain", port) == BridgeCheckTask.Verdict.NO, "bridges:a class that does not is not");
            that(BridgeCheckTask.implementsPort(h, "com/example/Nowhere", port) == BridgeCheckTask.Verdict.MISSING, "bridges:a class the project does not compile is missing");
            that(BridgeCheckTask.implementsPort(h, "com/example/Outside", port) == BridgeCheckTask.Verdict.UNSEEN,
                    "bridges:a class extending something outside the project cannot be judged, and says so");

            // the task, on a report that names each, with the levels of the default table
            Path dir = Files.createTempDirectory("mycelium-bridge-check-");
            try {
                Path report = dir.resolve("import.json");
                for (String[] c : new String[][] {{"com.example.Direct", "true"}, {"com.example.Plain", "false"}, {"com.example.Nowhere", "false"},
                        {"com.example.Outside", "true"}}) {
                    SelfTest.write(report, "{\"schema\":1,\"minecraft\":\"1.21.1\",\"bridges\":[{\"slug\":\"item-carrier\",\"id\":\"item.carrier\","
                            + "\"contract\":\"item.component@1\",\"port\":\"StackCarrier\",\"status\":\"override\",\"class\":\"" + c[0] + "\"}]}");
                    boolean holds = FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of()));
                    that(holds == Boolean.parseBoolean(c[1]), "bridges:B1 holds an override to the port (" + c[0] + " " + (holds ? "passes" : "fails") + ")");
                }
                SelfTest.write(report, "{\"schema\":1,\"minecraft\":\"1.21.1\",\"bridges\":[{\"slug\":\"item-carrier\",\"id\":\"item.carrier\","
                        + "\"contract\":\"item.component@1\",\"port\":\"StackCarrier\",\"status\":\"override\",\"class\":\"com.example.Plain\"}]}");
                that(FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of(Checks.encodeLevel("B1", Checks.Level.WARN)))),
                        "bridges:B1 can be lowered to warn");
                that(FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of(Checks.encodeAllowance("B1", "item-carrier", "ours is a test double")))),
                        "bridges:B1 can be allowed with a reason");
                SelfTest.write(report, "{\"schema\":1,\"minecraft\":\"1.12.2\",\"bridges\":[{\"slug\":\"item-carrier\",\"id\":\"item.carrier\","
                        + "\"contract\":\"item.component@1\",\"port\":\"StackCarrier\",\"status\":\"none\",\"why\":\"no names\"}]}");
                that(FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of())), "bridges:B2 warns by default for a bridge that cannot map on the target");
                that(!FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of(Checks.encodeLevel("B2", Checks.Level.FAIL)))),
                        "bridges:and fails when the mod says it should");
                JsonObject written = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("report/bridges.json"))).getAsJsonObject();
                that(written.getAsJsonArray("findings").size() == 1 && written.getAsJsonArray("bridges").get(0).getAsJsonObject().get("status").getAsString().equals("none"),
                        "bridges:the check's record carries the choice and what it found, for the evidence manifest: " + written);
                // no target version at all is a B2 as well, and it names itself without a Minecraft to name
                SelfTest.write(report, "{\"schema\":1,\"bridges\":[{\"slug\":\"item-carrier\",\"id\":\"item.carrier\","
                        + "\"contract\":\"item.component@1\",\"port\":\"StackCarrier\",\"status\":\"none\",\"why\":\"mycelium { minecraft = ... } is not set\"}]}");
                that(FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of())), "bridges:B2 warns for a build with no target version");
                that(!FlexibleSelfTest.runs(checkTask(dir, report, classes, List.of(Checks.encodeLevel("B2", Checks.Level.FAIL)))),
                        "bridges:and can fail it");
                JsonObject noVersion = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("report/bridges.json"))).getAsJsonObject();
                that(noVersion.getAsJsonArray("findings").size() == 1 && noVersion.getAsJsonArray("findings").toString().contains("no target version"),
                        "bridges:the finding for a build with no version says so: " + noVersion.getAsJsonArray("findings"));

                // an override in another source set (a client one) is found there; one found nowhere is a typo only when every output was looked in
                Path client = Files.createDirectories(dir.resolve("client-classes"));
                Files.createDirectories(client.resolve("com/example"));
                Files.write(client.resolve("com/example/InClient.class"), clazz("com/example/InClient", "java/lang/Object", port));
                Path unbuilt = dir.resolve("gametest-classes-never-built");
                java.util.function.BiFunction<String, List<Path>, Boolean> holds = (name, others) -> {
                    try {
                        SelfTest.write(report, "{\"schema\":1,\"minecraft\":\"1.21.1\",\"bridges\":[{\"slug\":\"item-carrier\",\"id\":\"item.carrier\","
                                + "\"contract\":\"item.component@1\",\"port\":\"StackCarrier\",\"status\":\"override\",\"class\":\"" + name + "\"}]}");
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return FlexibleSelfTest.runs(checkTask(dir, report, classes, others, List.of()));
                };
                that(holds.apply("com.example.InClient", List.of(client)), "bridges:B1 finds an override in another source set's output (it is not a typo)");
                that(!holds.apply("com.example.Nowhere", List.of(client)), "bridges:an override found in no output, every output looked in, is missing (a typo)");
                that(holds.apply("com.example.Nowhere", List.of(client, unbuilt)),
                        "bridges:an override found nowhere while another source set was not built is unseen, not missing: it may live there");
                JsonObject unseen = com.google.gson.JsonParser.parseString(Files.readString(dir.resolve("report/bridges.json"))).getAsJsonObject();
                that(unseen.getAsJsonArray("bridges").get(0).getAsJsonObject().has("unverified") && unseen.getAsJsonArray("findings").isEmpty(),
                        "bridges:and the record says it could not verify it: " + unseen);
            } finally {
                SelfTest.delete(dir);
            }
        } finally {
            SelfTest.delete(classes);
        }
    }

    private static BridgeCheckTask checkTask(Path dir, Path report, Path classes, List<String> checks) {
        return checkTask(dir, report, classes, List.of(), checks);
    }

    private static BridgeCheckTask checkTask(Path dir, Path report, Path classes, List<Path> others, List<String> checks) {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        BridgeCheckTask t = p.getTasks().register("bridges" + System.nanoTime(), BridgeCheckTask.class).get();
        t.getRoot().set(Path.of("..").toAbsolutePath().normalize().toFile());
        t.getNamespace().set("com.example.mod.mycelium");
        t.getChecks().set(checks);
        t.getImportReport().set(report.toFile());
        t.getClasses().from(classes.toFile());
        others.forEach(other -> t.getOtherClasses().from(other.toFile()));
        t.getReportDir().set(dir.resolve("report").toFile());
        return t;
    }

    // --- the plugin's wiring, on a throwaway project -----------------------------------------------------------------------------

    static void wiring(Path root, Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("wiring"));
        Project p = FlexibleSelfTest.mod(dir, "wiring", null, root);
        MyceliumExtension ext = p.getExtensions().getByType(MyceliumExtension.class);
        ext.getRoot().set(root.toFile());
        ext.getMinecraft().set("1.21.1");
        ext.getNamespace().set("fixture.bridges.mycelium");
        ((org.gradle.api.internal.project.ProjectInternal) p).evaluate();
        org.gradle.api.tasks.SourceSetContainer sets = p.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets();
        Path bridges = p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/bridges/java").get().getAsFile().toPath();
        Path conformance = p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/conformance/java").get().getAsFile().toPath();
        that(sets.getByName("main").getJava().getSrcDirs().stream().anyMatch(d -> d.toPath().equals(bridges)),
                "bridges:the imported bridges are part of the mod's main sources");
        that(sets.getByName("main").getJava().getSrcDirs().stream().noneMatch(d -> d.toPath().equals(conformance)),
                "bridges:the conformance cases are not, unless the mod asks");
        Set<String> compile = new HashSet<>();
        reachable(p.getTasks().getByName("compileJava")).forEach(t -> compile.add(t.getName()));
        Set<String> check = new HashSet<>();
        reachable(p.getTasks().getByName("check")).forEach(t -> check.add(t.getName()));
        that(compile.contains("myceliumBridgeImport") && !compile.contains("myceliumBridges"),
                "bridges:the sources are imported before the mod compiles, and the check waits for the compile: " + compile);
        that(check.contains("myceliumBridges"), "bridges:check holds the bridges (B1, B2): " + check);
        Set<String> evidence = new HashSet<>();
        reachable(p.getTasks().getByName("myceliumEvidence")).forEach(t -> evidence.add(t.getName()));
        that(evidence.contains("myceliumBridges"), "bridges:the evidence manifest waits for the bridges' record");

        // What a task reads from the Mycelium checkout is tracked as files when the build runs. A fingerprint computed while the build
        // is configured is stored in the configuration cache with its value: it stays right only while Gradle tracks the read that made
        // it (and then every edit throws the whole configuration away). The fixture cache-consumer proves the file inputs end to end.
        Path checkout = root.toAbsolutePath().normalize();
        java.util.function.BiPredicate<String, String> tracks = (task, relative) -> p.getTasks().getByName(task).getInputs().getFiles().getFiles().stream()
                .anyMatch(f -> f.toPath().toAbsolutePath().normalize().equals(checkout.resolve(relative).normalize()));
        that(tracks.test("myceliumBridgeImport", "bridges") && tracks.test("myceliumBridgeImport", "eras") && tracks.test("myceliumBridgeImport", "contracts/registry.json"),
                "bridges:the bridge import tracks the bridges, the era table and the registry as files, not as a fingerprint taken at configuration");
        that(tracks.test("myceliumPackMeta", "eras/minecraft.json"), "bridges:the pack.mcmeta task tracks the era table as a file");
        that(tracks.test("myceliumVerify", "contracts/proofs.json"),
                "bridges:the verify task tracks the recorded proofs as a file, whether or not it exists when the build is configured");
        that(tracks.test("myceliumPresentation", "contracts/item-presentation/src/mycelium/item_presentation/v1/model/SigilTool.java")
                        && tracks.test("myceliumPresentation", "eras"),
                "bridges:the presentation task tracks the compiler's models and the era table as files");
        for (String name : List.of("myceliumBridgeImport", "myceliumPackMeta", "myceliumPresentation")) {
            that(java.util.Arrays.stream(p.getTasks().getByName(name).getClass().getMethods())
                            .noneMatch(m -> m.getName().equals("getSourceFingerprint") || m.getName().equals("getTableFingerprint")),
                    "bridges:" + name + " takes no fingerprint of the checkout as an input value (computed while the build is configured)");
        }

        // conformance(): the game-test sources are main unless the project has a gametest source set, or names one
        Path dir2 = Files.createDirectories(tmp.resolve("conf"));
        Project q = FlexibleSelfTest.mod(dir2, "conf", null, root);
        MyceliumExtension eq = q.getExtensions().getByType(MyceliumExtension.class);
        eq.getRoot().set(root.toFile());
        eq.getMinecraft().set("1.21.1");
        eq.bridges(b -> b.conformance());
        ((org.gradle.api.internal.project.ProjectInternal) q).evaluate();
        Path qc = q.getLayout().getBuildDirectory().dir("generated/sources/mycelium/conformance/java").get().getAsFile().toPath();
        that(q.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets().getByName("main").getJava().getSrcDirs().stream()
                .anyMatch(d -> d.toPath().equals(qc)), "bridges:conformance() joins main, where a mod that keeps its game tests beside its code has them");

        Path dir3 = Files.createDirectories(tmp.resolve("gametest"));
        Project g = FlexibleSelfTest.mod(dir3, "gametest", null, root);
        MyceliumExtension eg = g.getExtensions().getByType(MyceliumExtension.class);
        eg.getRoot().set(root.toFile());
        eg.getMinecraft().set("1.21.1");
        org.gradle.api.tasks.SourceSetContainer gs = g.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets();
        gs.create("gametest");
        eg.bridges(b -> b.conformance());
        ((org.gradle.api.internal.project.ProjectInternal) g).evaluate();
        Path gc = g.getLayout().getBuildDirectory().dir("generated/sources/mycelium/conformance/java").get().getAsFile().toPath();
        that(gs.getByName("gametest").getJava().getSrcDirs().stream().anyMatch(d -> d.toPath().equals(gc))
                        && gs.getByName("main").getJava().getSrcDirs().stream().noneMatch(d -> d.toPath().equals(gc)),
                "bridges:conformance() joins the gametest source set when the project has one");

        Path dir4 = Files.createDirectories(tmp.resolve("named"));
        Project n = FlexibleSelfTest.mod(dir4, "named", null, root);
        MyceliumExtension en = n.getExtensions().getByType(MyceliumExtension.class);
        en.getRoot().set(root.toFile());
        en.bridges(b -> b.conformance("nowhere"));
        boolean refused = false;
        try {
            ((org.gradle.api.internal.project.ProjectInternal) n).evaluate();
        } catch (RuntimeException e) {
            refused = String.valueOf(e.getMessage()).contains("names a source set") || String.valueOf(e.getCause()).contains("names a source set");
        }
        that(refused, "bridges:conformance(\"nowhere\") names a source set the project lacks, and is refused by name");

        // declared once: the root's bridges block is every project's
        Project top = ProjectBuilder.builder().withProjectDir(Files.createDirectories(tmp.resolve("top")).toFile()).withName("top").build();
        top.getPluginManager().apply(MyceliumDefaultsPlugin.class);
        MyceliumExtension d = top.getExtensions().getByType(MyceliumDefaultsPlugin.Defaults.class);
        d.getRoot().set(root.toFile());
        d.bridges(b -> {
            b.override("item-carrier", "com.example.MyCarrier");
            b.conformance();
            b.skip("other");
            b.shipConformanceResources();
            b.conformanceClassesOutOfJar();
        });
        Path programs = Files.createDirectories(tmp.resolve("top/presentation"));
        d.presentation(sp -> sp.getSources().set(programs.toFile()));
        Project a = FlexibleSelfTest.mod(Files.createDirectories(tmp.resolve("top/a")), "a", top, root);
        MyceliumExtension ea = a.getExtensions().getByType(MyceliumExtension.class);
        that(ea.getBridges().getOverrides().get().equals(Map.of("item-carrier", "com.example.MyCarrier")) && ea.getBridges().getConformance().get()
                        && ea.getBridges().getSkip().get().equals(List.of("other")),
                "bridges:a bridges block written once at the root is every project's");
        that(ea.getBridges().getShipConformanceResources().get() && ea.getBridges().getConformanceClassesOutOfJar().get(),
                "bridges:what the conformance cases keep out of the jar is declared once, at the root");
        that(ea.getPresentation().getSources().get().getAsFile().toPath().equals(programs)
                        && ea.getPresentation().getComponents().get().getAsFile().toPath().equals(programs.resolve("components.json"))
                        && ea.getPresentation().getLock().get().getAsFile().toPath().equals(programs.resolve("presentation.lock.json")),
                "presentation:the programs' home written once at the root is every project's, with the catalog and the lock beside them");
        that(SelfTest.refuses(() -> ea.getBridges().override("item-carrier", ""), "takes a bridge slug and a fully qualified class name"),
                "bridges:an override without a class is refused where it is written");

        // contracts follow the bridges a mod names: it implements that contract's port
        Path src = Files.createDirectories(dir.resolve("src/main/java/mod"));
        SelfTest.write(src.resolve("Mod.java"), "package mod;\nclass Mod { fixture.bridges.mycelium.bridges.item_carrier.ItemCarrier c; }\n");
        List<String> ids = MyceliumPlugin.deriveContracts(root.toFile(), sets.getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(),
                ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium").get().getAsFile());
        that(ids.equals(List.of("item.component@1")), "bridges:a source naming a bridge brings the contract whose port it implements: " + ids);
        java.io.File generatedRoot = p.getLayout().getBuildDirectory().dir("generated/sources/mycelium").get().getAsFile();
        MyceliumPlugin.Derived named = MyceliumPlugin.derive(root.toFile(), sets.getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(),
                ext.getNamespace().get(), generatedRoot);
        that(named.bridges().equals(List.of("item-carrier")), "bridges:a source naming a bridge's package takes that bridge: " + named.bridges());
        // importing a contract's model is not asking for its bridge (offer, don't police): only a reference to the bridge's own package is
        Path modelOnly = Files.createDirectories(dir.resolve("modelonly/java/mod2"));
        SelfTest.write(modelOnly.resolve("Mod2.java"), "package mod2;\nclass Mod2 { fixture.bridges.mycelium.item_component.v1.model.StackCarrier<Object> c; }\n");
        MyceliumPlugin.Derived modelAlone = MyceliumPlugin.derive(root.toFile(), List.of(dir.resolve("modelonly/java").toFile()), List.of(), ext.getNamespace().get(), generatedRoot);
        that(modelAlone.contracts().equals(List.of("item.component@1")) && modelAlone.bridges().isEmpty(),
                "bridges:a source that uses only the contract's model takes the contract and none of its bridges: " + modelAlone);
        // the derivation reads the languages a mod is written in, not only Java
        Path kotlin = Files.createDirectories(dir.resolve("kotlin/kt/mod3"));
        SelfTest.write(kotlin.resolve("Mod3.kt"), "package mod3\nclass Mod3 { val c: fixture.bridges.mycelium.bridges.world_clock.conformance.ClockConformance? = null }\n");
        SelfTest.write(kotlin.resolve("Mod4.scala"), "package mod3\nclass Mod4 { val s: fixture.bridges.mycelium.world_copy.v1.model.WorldCopy = null }\n");
        MyceliumPlugin.Derived otherLanguages = MyceliumPlugin.derive(root.toFile(), List.of(dir.resolve("kotlin").toFile()), List.of(), ext.getNamespace().get(), generatedRoot);
        that(otherLanguages.bridges().equals(List.of("world-clock")) && otherLanguages.contracts().contains("world.clock@1") && otherLanguages.contracts().contains("world.copy@1"),
                "bridges:a Kotlin or Scala source referencing a bridge or a contract is a use, as a Java one is: " + otherLanguages);
        that(DeriveContractsTask.withBridgeContracts(root.toFile(), List.of("random.seeded@1"), List.of("item-carrier"))
                        .equals(List.of("random.seeded@1", "item.component@1")),
                "bridges:use(\"item-carrier\") brings its contract as well");
        // what the import wrote is not the mod's own use of anything
        Path generated = Files.createDirectories(p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/bridges/java/fixture").get().getAsFile().toPath());
        SelfTest.write(generated.resolve("Gen.java"), "package fixture;\nclass Gen { fixture.bridges.mycelium.data_catalog.v1.model.Catalog c; }\n");
        ids = MyceliumPlugin.deriveContracts(root.toFile(), sets.getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(),
                ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium").get().getAsFile());
        that(!ids.contains("data.catalog@1"), "bridges:what a bridge's import wrote is the import, not a use: " + ids);
        that(MyceliumPlugin.isImported(generated.toFile(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium").get().getAsFile())
                        && !MyceliumPlugin.isImported(src.toFile(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium").get().getAsFile()),
                "bridges:the imported root holds every import, and nothing of the mod's own");

        // the derive task writes what the mod takes: the bridges its sources reference, or the ones it names; offer, don't police
        DeriveContractsTask derive = (DeriveContractsTask) p.getTasks().getByName("myceliumDeriveContracts");
        derive.run();
        Path bridgeList = p.getLayout().getBuildDirectory().file("mycelium/bridges.txt").get().getAsFile().toPath();
        Path contractList = p.getLayout().getBuildDirectory().file("mycelium/contracts.txt").get().getAsFile().toPath();
        that(Files.readString(bridgeList).strip().equals("item-carrier") && Files.readString(contractList).strip().equals("item.component@1"),
                "bridges:the derive task lists the bridges the sources reference, and their contract: " + Files.readString(bridgeList) + " / " + Files.readString(contractList));
        ext.getBridges().use("world-clock");
        derive.run();
        that(Files.readString(bridgeList).strip().equals("world-clock") && Files.readString(contractList).contains("world.clock@1"),
                "bridges:use(...) names exactly the bridges to take, whatever the sources reference, with their contracts: " + Files.readString(bridgeList));
        ext.getBridges().getUse().set(List.of());

        // presentation: the catalog and the lock are inputs (they may sit outside the sources), and the work is kept apart from the outputs
        PresentationTask presentation = (PresentationTask) p.getTasks().getByName("myceliumPresentation");
        PresentationLockTask presentationLock = (PresentationLockTask) p.getTasks().getByName("myceliumPresentationLock");
        Path presentationSources = dir.resolve("src/main/presentation");
        that(presentation.getCatalogFiles().getFiles().stream().anyMatch(f -> f.toString().replace((char) 92, (char) 47).endsWith("src/main/presentation/components.json"))
                        && presentation.getCatalogFiles().getFiles().stream().anyMatch(f -> f.toString().replace((char) 92, (char) 47).endsWith("src/main/presentation/presentation.lock.json")),
                "bridges:the presentation task takes the catalog and the lock as inputs: " + presentation.getCatalogFiles().getFiles());
        Path presentationWork = presentation.getWorkDir().get().getAsFile().toPath();
        that(!presentationWork.startsWith(presentation.getReportDir().get().getAsFile().toPath()) && !presentationWork.startsWith(presentation.getOutputDir().get().getAsFile().toPath()),
                "bridges:the presentation task keeps its compiled model outside the directories it owns and rewrites: " + presentationWork);
        that(!presentationLock.getWorkDir().get().getAsFile().equals(presentation.getWorkDir().get().getAsFile()),
                "bridges:the lock task does not share the build task's work directory");

        // a contract used through its bridge is used (U1)
        Project u = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        UsageTask t = u.getTasks().register("usageThroughBridge", UsageTask.class).get();
        t.getRoot().set(root.toFile());
        t.getContracts().set(List.of("item.component@1"));
        t.getNamespace().set("fixture.bridges.mycelium");
        t.getFailOnUnused().set(true);
        t.getChecks().set(List.of());
        t.getSources().from(src.toFile());
        t.getReportDir().set(dir.resolve("usage").toFile());
        that(FlexibleSelfTest.runs(t), "bridges:a contract the mod uses only through its bridge is used (U1)");
    }

    private static Set<org.gradle.api.Task> reachable(org.gradle.api.Task from) {
        Set<org.gradle.api.Task> seen = new HashSet<>();
        List<org.gradle.api.Task> todo = new ArrayList<>(List.of(from));
        while (!todo.isEmpty()) {
            org.gradle.api.Task t = todo.remove(todo.size() - 1);
            for (org.gradle.api.Task d : t.getTaskDependencies().getDependencies(t)) {
                if (seen.add(d)) {
                    todo.add(d);
                }
            }
        }
        return seen;
    }

    // --- the repository check, shown to fire on each way a bridge can be broken ----------------------------------------------------

    static void repository(Path root) throws IOException {
        Path base = SelfTest.copy(root);
        java.util.Set<String> baseline = SelfTest.findings(base);
        SelfTest.delete(base);
        that(baseline.stream().noneMatch(f -> f.startsWith("[bridges]") || f.contains("item.carrier")), "bridges:the repository as it stands has no finding about its bridges: " + baseline);
        String carrierJson = "bridges/item-carrier/bridge.json";
        String model = "contracts/item-component/src/mycelium/item_component/v1/model/StackCarrier.java";
        SelfTest.expect(root, "an overlay outside its variant's range", r -> SelfTest.replace(r.resolve(carrierJson), "\"from\": \"1.21.5\"", "\"from\": \"1.20.1\""),
                f -> f.contains("[bridges]") && f.contains("overlay 1.21.5 of custom-data covers Minecraft 1.20.4") && f.contains("'root-tag'"), baseline);
        SelfTest.expect(root, "an overlay that runs backwards", r -> SelfTest.replace(r.resolve(carrierJson), "\"from\": \"1.14\", \"to\": \"1.14.4\"", "\"from\": \"1.14.4\", \"to\": \"1.14\""),
                f -> f.contains("[bridges]") && f.contains("runs backwards"), baseline);
        SelfTest.expect(root, "an overlay bound that names no real version", r -> SelfTest.replace(r.resolve(carrierJson), "\"to\": \"1.14.4\"", "\"to\": \"1.14.44\""),
                f -> f.contains("[bridges]") && f.contains("bound '1.14.44' is not a version the launcher names"), baseline);
        SelfTest.expect(root, "two overlays that meet", r -> {
                    SelfTest.replace(r.resolve(carrierJson), "\"overlays\": [\n        {\"name\": \"1.14\"", "\"overlays\": [\n        {\"name\": \"1.14b\", \"from\": \"1.14.2\", \"to\": \"1.14.4\"},\n        {\"name\": \"1.14\"");
                    SelfTest.copyTree(r.resolve("bridges/item-carrier/root-tag/overlays/1.14"), r.resolve("bridges/item-carrier/root-tag/overlays/1.14b"));
                },
                f -> f.contains("[bridges]") && f.contains("cover the same version"), baseline);
        SelfTest.expect(root, "an overlay with no files", r -> SelfTest.delete(r.resolve("bridges/item-carrier/root-tag/overlays/1.14")),
                f -> f.contains("[bridges]") && f.contains("overlay 1.14 of root-tag has no files"), baseline);
        SelfTest.expect(root, "an overlay directory nothing lists", r -> SelfTest.write(r.resolve("bridges/item-carrier/root-tag/overlays/ghost/src/mycelium/bridges/item_carrier/Nbt.java"),
                        "package mycelium.bridges.item_carrier;\nclass Nbt {}\n"),
                f -> f.contains("[bridges]") && f.contains("overlays/ghost") && f.contains("no version draws it"), baseline);
        SelfTest.expect(root, "a table row for a variant the bridge lacks", r -> SelfTest.replace(r.resolve("eras/minecraft.json"), "\"realization\": \"root-tag\"", "\"realization\": \"nbt-tag\""),
                f -> f.contains("[bridges]") && f.contains("'nbt-tag'") && f.contains("no variant for"), baseline);
        SelfTest.expect(root, "a variant no row draws", r -> SelfTest.replace(r.resolve("eras/minecraft.json"), "\"realization\": \"root-tag\"", "\"realization\": \"custom-data\""),
                f -> f.contains("[bridges]") && f.contains("variant root-tag is drawn by no row"), baseline);
        SelfTest.expect(root, "a gap in the carrier table", r -> SelfTest.replace(r.resolve("eras/minecraft.json"), "\"from\": \"1.14\", \"to\": \"1.20.4\"", "\"from\": \"1.15\", \"to\": \"1.20.4\""),
                f -> f.contains("Minecraft 1.14 falls in no item.carrier row"), baseline);
        SelfTest.expect(root, "an overlap in the carrier table", r -> SelfTest.replace(r.resolve("eras/minecraft.json"), "\"from\": \"1.14\", \"to\": \"1.20.4\"", "\"from\": \"1.14\", \"to\": \"1.20.5\""),
                f -> f.contains("Minecraft 1.20.5 falls in several item.carrier rows"), baseline);
        SelfTest.expect(root, "a bridge whose contract does not list it", r -> SelfTest.replace(r.resolve("contracts/registry.json"), "\"bridges\": [\"item-carrier\"],", ""),
                f -> f.contains("[bridges]") && f.contains("whose registry row does not list it"), baseline);
        SelfTest.expect(root, "a row listing a bridge that is not there", r -> SelfTest.replace(r.resolve("contracts/registry.json"), "[\"item-carrier\"]", "[\"item-carrier\", \"ghost\"]"),
                f -> f.contains("[bridges]") && f.contains("lists the bridge 'ghost'"), baseline);
        SelfTest.expect(root, "a port that is not in the model", r -> SelfTest.delete(r.resolve(model)),
                f -> f.contains("[bridges]") && f.contains("the port StackCarrier is not in item.component@1's model"), baseline);
        SelfTest.expect(root, "a variant without its implementation", r -> SelfTest.delete(r.resolve("bridges/item-carrier/root-tag/src/mycelium/bridges/item_carrier/ItemCarrier.java")),
                f -> f.contains("[bridges]") && f.contains("variant root-tag does not define ItemCarrier"), baseline);
        SelfTest.expect(root, "an implementation that is not the port", r -> SelfTest.replace(r.resolve("bridges/item-carrier/root-tag/src/mycelium/bridges/item_carrier/ItemCarrier.java"),
                        "implements StackCarrier<ItemStack>", "implements Runnable"),
                f -> f.contains("[bridges]") && f.contains("does not implement StackCarrier"), baseline);
        SelfTest.expect(root, "a variant without the rig the conformance cases need", r -> SelfTest.delete(r.resolve("bridges/item-carrier/root-tag/conformance")),
                f -> f.contains("[bridges]") && f.contains("none of its own to run them with"), baseline);
        SelfTest.expect(root, "a source in a package that is not the bridge's", r -> SelfTest.replace(r.resolve("bridges/item-carrier/root-tag/src/mycelium/bridges/item_carrier/Nbt.java"),
                        "package mycelium.bridges.item_carrier;", "package mycelium.bridges.elsewhere;"),
                f -> f.contains("[bridges]") && f.contains("declares package 'mycelium.bridges.elsewhere' but sits in"), baseline);
        SelfTest.expect(root, "a conformance rig that reaches the bridge's own class", r -> SelfTest.append(
                        r.resolve("bridges/item-carrier/custom-data/conformance/mycelium/bridges/item_carrier/conformance/Wire.java"),
                        "// mycelium.bridges.item_carrier.ItemCarrier\n"),
                f -> f.contains("[bridges]") && f.contains("part of the conformance rig an override imports") && f.contains("conformance-bound/"), baseline);
        SelfTest.expect(root, "the cases themselves reaching the bridge's own class", r -> SelfTest.append(
                        r.resolve("bridges/world-clock/conformance/mycelium/bridges/world_clock/conformance/ClockCases.java"),
                        "// mycelium.bridges.world_clock.WorldClocks\n"),
                f -> f.contains("[bridges]") && f.contains("ClockCases.java") && f.contains("part of the conformance rig an override imports"), baseline);
        SelfTest.expect(root, "a bound conformance class reaching the bridge's own class", r -> SelfTest.append(
                        r.resolve("bridges/world-clock/native-clock/conformance-bound/mycelium/bridges/world_clock/conformance/ClockConformance.java"),
                        "// mycelium.bridges.world_clock.WorldClocks\n"),
                f -> f.contains("[bridges]") && f.contains("part of the conformance rig"), baseline, false);
        SelfTest.expect(root, "a bound conformance class in the wrong package", r -> SelfTest.replace(
                        r.resolve("bridges/world-clock/native-clock/conformance-bound/mycelium/bridges/world_clock/conformance/ClockConformance.java"),
                        "package mycelium.bridges.world_clock.conformance;", "package mycelium.bridges.elsewhere;"),
                f -> f.contains("[bridges]") && f.contains("declares package 'mycelium.bridges.elsewhere' but sits in"), baseline);
        SelfTest.expect(root, "a bridge reaching the harness", r -> SelfTest.append(r.resolve("bridges/item-carrier/root-tag/src/mycelium/bridges/item_carrier/Nbt.java"),
                        "// mycelium.harness.Check\n"),
                f -> f.contains("[bridges]") && f.contains("mentions mycelium.harness"), baseline);
        SelfTest.expect(root, "a bridge.json field nothing reads", r -> SelfTest.replace(r.resolve(carrierJson), "\"schema\": 1,", "\"schema\": 1, \"liveConsumers\": [],"),
                f -> f.contains("[bridges]") && f.contains("unknown field 'liveConsumers'"), baseline);
        SelfTest.expect(root, "a bridge written in names nobody has data for", r -> SelfTest.replace(r.resolve(carrierJson), "\"writtenIn\": [\"mojang\", \"unobfuscated\"]", "\"writtenIn\": [\"mojang\", \"srg2\"]"),
                f -> f.contains("[bridges]") && f.contains("'srg2'"), baseline);
        SelfTest.expect(root, "name data no loader and no bridge reads", r -> {
            SelfTest.replace(r.resolve(carrierJson), "\"writtenIn\": [\"mojang\", \"unobfuscated\"]", "\"writtenIn\": [\"unobfuscated\"]");
            SelfTest.replace(r.resolve("bridges/world-clock/bridge.json"), "\"writtenIn\": [\"mojang\", \"unobfuscated\"]", "\"writtenIn\": [\"unobfuscated\"]");
            SelfTest.replace(r.resolve("bridges/item-presentation/bridge.json"), "\"writtenIn\": [\"mojang\", \"unobfuscated\"]", "\"writtenIn\": [\"unobfuscated\"]");
        },
                f -> f.contains("[names]") && f.contains("mojang 1.14.4 is data no loader's runtime is named mojang on") && f.contains("no bridge is written in mojang"), baseline);
        SelfTest.expect(root, "a directory under bridges/ that is not a bridge", r -> SelfTest.mkdirs(r.resolve("bridges/not-a-bridge")),
                f -> f.contains("[bridges]") && f.contains("has no bridge.json"), baseline);
        SelfTest.expect(root, "a document naming a bridge file that is not there", r -> SelfTest.append(r.resolve("AGENTS.md"), "\nSee `bridges/item-carrier/Nowhere.java`.\n"),
                f -> f.contains("Nowhere.java"), baseline);
    }

    // --- linkage, on a game built here ---------------------------------------------------------------------------------------------

    /** A game with one class, {@code net.minecraft.test.Stack}, whose {@code count()} it has or lacks; written as a jar. */
    private static Path game(Path dir, String name, boolean hasCount) throws IOException {
        Path src = dir.resolve(name + "-src/net/minecraft/test/Stack.java");
        SelfTest.write(src, "package net.minecraft.test;\npublic class Stack {\n    public static Stack of() { return new Stack(); }\n"
                + (hasCount ? "    public int count() { return 1; }\n" : "") + "}\n");
        Path classes = Files.createDirectories(dir.resolve(name + "-classes"));
        Process p = new ProcessBuilder(BridgeLinkage.runningJavac().toString(), "--release", "17", "-d", classes.toString(), src.toString())
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (p.waitFor() != 0) {
                throw new IllegalStateException("self-test setup: the fake game did not compile: " + output);
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        Path jar = dir.resolve(name + ".jar");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("net/minecraft/test/Stack.class"));
            z.write(Files.readAllBytes(classes.resolve("net/minecraft/test/Stack.class")));
            z.closeEntry();
        }
        return jar;
    }

    private static String entry(String version, Path jar) throws IOException {
        return "\"" + version + "\":{\"inputs\":[{\"role\":\"game\",\"url\":\"" + jar.toUri() + "\",\"sha256\":\"" + NameCache.sha256(jar) + "\"}]}";
    }

    static void linkage(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("linkage"));
        Path models = Files.createDirectories(dir.resolve("models/mycelium/fake/v1/model"));
        SelfTest.write(models.resolve("Port.java"), "package mycelium.fake.v1.model;\npublic interface Port {\n    int n();\n}\n");
        Path bridgeDir = Files.createDirectories(dir.resolve("bridges/mini"));
        SelfTest.write(bridgeDir.resolve("bridge.json"), "{\"schema\":1,\"id\":\"mini\",\"contract\":\"fake@1\",\"port\":\"Port\",\"implementation\":\"Impl\","
                + "\"package\":\"mycelium.bridges.mini\",\"divide\":\"mini.divide\",\"writtenIn\":[\"unobfuscated\"],"
                + "\"variants\":{\"only\":{\"overlays\":[]}}}");
        SelfTest.write(bridgeDir.resolve("only/src/mycelium/bridges/mini/Impl.java"),
                "package mycelium.bridges.mini;\nimport mycelium.fake.v1.model.Port;\nimport net.minecraft.test.Stack;\n"
                        + "public class Impl implements Port {\n    public int n() { return Stack.of().count(); }\n}\n");
        Bridges.Bridge bridge = Bridges.parse(Files.readString(bridgeDir.resolve("bridge.json")), "mini", bridgeDir, "mini");
        Eras eras = Eras.parse("{\"schema\":2,\"eras\":[],\"realizations\":{\"mini.divide\":[{\"from\":\"26.1\",\"to\":\"26.3\",\"realization\":\"only\"}]}}", "t");
        List<String> versions = List.of("26.3", "26.2", "26.1");
        Path with = game(dir, "with", true);
        Path without = game(dir, "without", false);
        NameCache cache = new NameCache(dir.resolve("cache"), false);
        Path javac = BridgeLinkage.runningJavac();
        List<Path> modelRoots = List.of(dir.resolve("models"));

        NameData fine = NameData.parse(lock(entry("26.3", with) + "," + entry("26.2", with)), "fine");
        BridgeLinkage.Result ok = BridgeLinkage.bridge(bridge, modelRoots, eras, fine, cache, versions, dir.resolve("work-ok"), javac);
        that(ok.findings().isEmpty(), "bridges:linkage: a variant whose references exist on every version with name data links: " + ok.findings());
        JsonObject group = ok.report().getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonArray("groups").get(0).getAsJsonObject();
        that(group.get("compiledAgainst").getAsString().equals("26.3") && group.getAsJsonArray("linked").size() == 2
                        && group.getAsJsonArray("noNameData").size() == 1 && group.get("status").getAsString().equals("linked"),
                "bridges:linkage: it was compiled against the newest version with data, linked on each, and the one without data is named: " + group);
        that(ok.notes().size() == 1 && ok.notes().get(0).contains("26.1"), "bridges:linkage: a version with no name data is reported, not failed (decision 0017): " + ok.notes());

        // the planted reference: a method one version of the claim lacks
        NameData lacksOlder = NameData.parse(lock(entry("26.3", with) + "," + entry("26.2", without)), "lacks");
        BridgeLinkage.Result older = BridgeLinkage.bridge(bridge, modelRoots, eras, lacksOlder, cache, versions, dir.resolve("work-older"), javac);
        that(older.findings().size() == 1 && older.findings().get(0).contains("Minecraft 26.2") && older.findings().get(0).contains("net/minecraft/test/Stack.count()I")
                        && older.findings().get(0).contains("bridge mini, variant only"),
                "bridges:linkage: a method a version of the claim lacks fails there, naming the bridge, the variant, the version and the reference: " + older.findings());

        // the planted reference on the version it is compiled against: a compile error that names it
        NameData lacksNewest = NameData.parse(lock(entry("26.3", without) + "," + entry("26.2", with)), "newest");
        BridgeLinkage.Result newest = BridgeLinkage.bridge(bridge, modelRoots, eras, lacksNewest, cache, versions, dir.resolve("work-newest"), javac);
        that(newest.findings().size() == 1 && newest.findings().get(0).contains("does not compile against Minecraft 26.3")
                        && newest.findings().get(0).contains("count"), "bridges:linkage: a variant that does not compile where it claims to fails, naming the symbol: " + newest.findings());

        // no data at all is reported and nothing is claimed
        NameData nothing = NameData.parse(lock(entry("26.3", with)), "none");
        BridgeLinkage.Result bare = BridgeLinkage.bridge(bridge, modelRoots, Eras.parse("{\"schema\":2,\"eras\":[],\"realizations\":{\"mini.divide\":[{\"from\":\"26.1\","
                + "\"to\":\"26.2\",\"realization\":\"only\"}]}}", "t"), nothing, cache, versions, dir.resolve("work-bare"), javac);
        that(bare.findings().isEmpty() && bare.report().getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonArray("groups").get(0).getAsJsonObject()
                        .get("status").getAsString().equals("unlinked"), "bridges:linkage: a group none of whose versions has data is unlinked, not linked: " + bare.report());
        // a compiler that is not there is not a compile that failed
        BridgeLinkage.Result nocompiler = BridgeLinkage.bridge(bridge, modelRoots, eras, fine, cache, versions, dir.resolve("work-nojavac"), dir.resolve("no-such-javac"));
        that(nocompiler.findings().isEmpty() && nocompiler.notes().stream().anyMatch(n -> n.contains("no Java compiler")),
                "bridges:linkage: without a Java compiler a variant is left unlinked and the note says why: " + nocompiler.notes());
        // an overlay is a group of its own, with its own compile
        Path overlaid = Files.createDirectories(dir.resolve("bridges/mini2"));
        SelfTest.copyTree(bridgeDir, overlaid);
        SelfTest.write(overlaid.resolve("bridge.json"), "{\"schema\":1,\"id\":\"mini\",\"contract\":\"fake@1\",\"port\":\"Port\",\"implementation\":\"Impl\","
                + "\"package\":\"mycelium.bridges.mini2\",\"divide\":\"mini.divide\",\"writtenIn\":[\"unobfuscated\"],"
                + "\"variants\":{\"only\":{\"overlays\":[{\"name\":\"late\",\"from\":\"26.3\",\"to\":\"26.3\"}]}}}");
        // (the package is the bridge's own: move the sources there)
        Files.createDirectories(overlaid.resolve("only/src/mycelium/bridges/mini2"));
        Files.move(overlaid.resolve("only/src/mycelium/bridges/mini/Impl.java"), overlaid.resolve("only/src/mycelium/bridges/mini2/Impl.java"));
        SelfTest.replace(overlaid.resolve("only/src/mycelium/bridges/mini2/Impl.java"), "package mycelium.bridges.mini;", "package mycelium.bridges.mini2;");
        SelfTest.write(overlaid.resolve("only/overlays/late/src/mycelium/bridges/mini2/Impl.java"),
                "package mycelium.bridges.mini2;\nimport mycelium.fake.v1.model.Port;\nimport net.minecraft.test.Stack;\n"
                        + "public class Impl implements Port {\n    public int n() { return Stack.of().count() + 1; }\n}\n");
        Bridges.Bridge withOverlay = Bridges.parse(Files.readString(overlaid.resolve("bridge.json")), "mini2", overlaid, "mini2");
        BridgeLinkage.Result two = BridgeLinkage.bridge(withOverlay, modelRoots, eras, NameData.parse(lock(entry("26.3", with) + "," + entry("26.2", with)), "two"),
                cache, versions, dir.resolve("work-two"), javac);
        JsonArray groups = two.report().getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonArray("groups");
        that(groups.size() == 2 && two.findings().isEmpty(), "bridges:linkage: the versions an overlay covers are a group of their own with their own compile: " + groups);

        // what was proved is said in numbers, so a run that linked little does not read as one that linked everything
        that(BridgeLinkage.summary(ok.report()).equals("linked 2 of 3 version(s), 1 not linked (no name data: 1; the game or a compiler for it could not be had: 0; do not link: 0)"),
                "bridges:linkage: the summary counts what linked and why the rest did not: " + BridgeLinkage.summary(ok.report()));
        that(BridgeLinkage.summary(nocompiler.report()).startsWith("linked 0 of 3 version(s), 3 not linked (no name data: 1;")
                        && BridgeLinkage.summary(nocompiler.report()).contains("could not be had: 2;"),
                "bridges:linkage: with no compiler nothing is linked, and the summary says 0 of 3: " + BridgeLinkage.summary(nocompiler.report()));
        that(BridgeLinkage.totals(nocompiler.report())[0] == 0 && BridgeLinkage.totals(nocompiler.report())[1] == 3,
                "bridges:linkage: the totals a run that linked nothing warns by are 0 of 3");
        that(BridgeLinkage.summary(older.report()).contains("do not link: 1") && BridgeLinkage.summary(newest.report()).contains("do not link: 2"),
                "bridges:linkage: a version that does not link is counted as such: " + BridgeLinkage.summary(older.report()) + " / " + BridgeLinkage.summary(newest.report()));

        // what an override imports (the cases and the game's rig, none of the bridge's source) compiles against the game on its own
        Bridges.Bridge lawful = withCases(dir, "mini3", "static int game() { return net.minecraft.test.Stack.of().count(); }");
        BridgeLinkage.Result lawfulResult = BridgeLinkage.bridge(lawful, modelRoots, eras, fine, cache, versions, dir.resolve("work-lawful"), javac);
        JsonObject lawfulGroup = lawfulResult.report().getAsJsonArray("variants").get(0).getAsJsonObject().getAsJsonArray("groups").get(0).getAsJsonObject();
        that(lawfulResult.findings().isEmpty() && lawfulGroup.get("overrideConformance").getAsString().startsWith("ok"),
                "bridges:linkage: the cases an override imports compile without the bridge's source: " + lawfulResult.findings() + lawfulGroup);
        Bridges.Bridge reaching = withCases(dir, "mini4", "static int game() { return new mycelium.bridges.mini4.Impl().n(); }");
        BridgeLinkage.Result reachingResult = BridgeLinkage.bridge(reaching, modelRoots, eras, fine, cache, versions, dir.resolve("work-reaching"), javac);
        that(reachingResult.findings().size() == 1 && reachingResult.findings().get(0).contains("an override imports do not compile")
                        && reachingResult.findings().get(0).contains("conformance-bound/"),
                "bridges:linkage: a rig that reaches the bridge's own class is a finding, since an override could not import it: " + reachingResult.findings());
    }

    /** A bridge on the fake game with conformance cases (against the port), a rig ({@code rig} as a method of {@code Rig}) and a bound class. */
    private static Bridges.Bridge withCases(Path dir, String name, String rig) throws IOException {
        String pkg = "mycelium.bridges." + name;
        String path = pkg.replace('.', '/');
        Path b = Files.createDirectories(dir.resolve("bridges/" + name));
        SelfTest.write(b.resolve("bridge.json"), "{\"schema\":1,\"id\":\"mini\",\"contract\":\"fake@1\",\"port\":\"Port\",\"implementation\":\"Impl\","
                + "\"package\":\"" + pkg + "\",\"divide\":\"mini.divide\",\"writtenIn\":[\"unobfuscated\"],\"variants\":{\"only\":{\"overlays\":[]}}}");
        SelfTest.write(b.resolve("only/src/" + path + "/Impl.java"), "package " + pkg + ";\nimport mycelium.fake.v1.model.Port;\nimport net.minecraft.test.Stack;\n"
                + "public class Impl implements Port {\n    public int n() { return Stack.of().count(); }\n}\n");
        SelfTest.write(b.resolve("conformance/" + path + "/conformance/Cases.java"), "package " + pkg + ".conformance;\n"
                + "public final class Cases {\n    public static int run(mycelium.fake.v1.model.Port port) { return port.n() + Rig.game(); }\n}\n");
        SelfTest.write(b.resolve("only/conformance/" + path + "/conformance/Rig.java"), "package " + pkg + ".conformance;\nfinal class Rig {\n    " + rig + "\n}\n");
        SelfTest.write(b.resolve("only/conformance-bound/" + path + "/conformance/Bound.java"), "package " + pkg + ".conformance;\n"
                + "public final class Bound {\n    public static int ours() { return Cases.run(new " + pkg + ".Impl()); }\n}\n");
        return Bridges.parse(Files.readString(b.resolve("bridge.json")), name, b, name);
    }

    // --- the game as a classpath, in a cache several Gradle processes share ----------------------------------------------------------------

    /** Holds the lock on a file for a while, as another Gradle process filling the same cache would. */
    public static final class LockHolder {
        public static void main(String[] args) throws Exception {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(Path.of(args[0]), java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE); java.nio.channels.FileLock held = channel.lock()) {
                System.out.println("locked");
                System.out.flush();
                Thread.sleep(Long.parseLong(args[1]));
            }
        }
    }

    static void gameClasspath(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("classpath"));
        Path jar = game(dir, "shared", true);
        NameData data = NameData.parse(lock(entry("26.3", jar)), "shared");
        NameData.Entry entry = data.entry("unobfuscated", "26.3");
        NameCache cache = new NameCache(dir.resolve("cache"), false);
        Path out = dir.resolve("classpath-cache");
        String key = "v" + GameClasspath.LOGIC + "-" + entry.input("game").sha256();
        that(GameClasspath.LOGIC >= 1, "bridges:classpath: the logic has a version");

        // another process is filling this version: the fill waits for it, then finds what it left (the lock is real, and it is honoured)
        Files.createDirectories(out.resolve("games"));
        Path javaBin = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows") ? "java.exe" : "java");
        Process holder = new ProcessBuilder(javaBin.toString(), "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(),
                out.resolve("games").resolve(key + ".lock").toString(), "1500").redirectErrorStream(true).start();
        try {
            java.io.BufferedReader heard = new java.io.BufferedReader(new java.io.InputStreamReader(holder.getInputStream()));
            String said = heard.readLine();
            while (said != null && !said.equals("locked")) {
                said = heard.readLine(); // (a JVM may announce its options first)
            }
            that("locked".equals(said), "bridges:classpath: the other process holds the lock (" + said + ")");
            long started = System.nanoTime();
            List<Path> jars = GameClasspath.of(entry, cache, out);
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            that(waitedMs >= 1000, "bridges:classpath: a fill waits for the process holding the lock instead of writing beside it (" + waitedMs + " ms)");
            that(jars.size() == 1 && Files.isRegularFile(jars.get(0)) && jars.get(0).getFileName().toString().startsWith(key),
                    "bridges:classpath: the jar is keyed by the inputs and the version of the logic: " + jars);
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jars.get(0).toFile())) {
                that(zip.getEntry("net/minecraft/test/Stack.class") != null, "bridges:classpath: the jar is whole");
            }
        } finally {
            holder.waitFor();
        }
        try (var walk = Files.walk(out)) {
            that(walk.noneMatch(f -> f.getFileName().toString().endsWith(".part")), "bridges:classpath: no half-written file is left under the cache");
        }
        long marker = Files.getLastModifiedTime(out.resolve("games").resolve(key + ".list")).toMillis();
        GameClasspath.of(entry, cache, out);
        that(Files.getLastModifiedTime(out.resolve("games").resolve(key + ".list")).toMillis() == marker, "bridges:classpath: a version that is done is not filled again");

        // a writer that fails leaves nothing under the name (not a truncated file the next reader would open)
        Path target = out.resolve("games").resolve("thing.jar");
        that(SelfTest.refuses(() -> {
            try {
                GameClasspath.placed(target, part -> {
                    Files.writeString(part, "half");
                    throw new IOException("the disk went");
                });
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage());
            }
        }, "the disk went") && !Files.exists(target), "bridges:classpath: a write that fails leaves no file under the name");
        try (var walk = Files.walk(out.resolve("games"))) {
            that(walk.noneMatch(f -> f.getFileName().toString().endsWith(".part")), "bridges:classpath: ... and no part file");
        }
    }

    private static String lock(String entries) {
        return "{\"schema\":1,\"namespaces\":{\"unobfuscated\":{\"reads\":\"jar\",\"owns\":[\"net/minecraft/\"],\"versions\":{" + entries + "}}}}";
    }
}
