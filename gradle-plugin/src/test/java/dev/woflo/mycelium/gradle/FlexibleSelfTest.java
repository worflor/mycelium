package dev.woflo.mycelium.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.testfixtures.ProjectBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static dev.woflo.mycelium.gradle.SelfTest.that;

/**
 * Self-tests for docs/decisions/0017 "Offer, don't police": the check levels and allowances, the defaults table, nothing
 * blocking compilation, contracts derived from the mod's own sources, defaults declared once, the era table's pack formats
 * and what is generated from them, and the proofs a mod takes from Mycelium's own check.
 */
final class FlexibleSelfTest {
    private FlexibleSelfTest() {
    }

    static void run(Path root) throws Exception {
        levels();
        packMeta(root);
        overlays();
        purityLevels(root);
        isolationGates();
        usageAndSurface();
        rangeAndLinkageChecks(root);
        proofs(root);
        Path tmp = Files.createTempDirectory("mycelium-flexible-");
        try {
            compileIsNeverBlocked(root, tmp);
            derivedContracts(root, tmp);
            defaultsInherited(root, tmp);
            namespaces(root, tmp);
            fingerprints(tmp);
        } finally {
            SelfTest.delete(tmp);
        }
    }

    // --- levels, allowances, the table ---------------------------------------------------------------------------------

    static void levels() {
        Set<String> failing = new HashSet<>();
        Checks.TABLE.values().forEach(c -> {
            if (c.def() == Checks.Level.FAIL) {
                failing.add(c.id());
            }
        });
        that(failing.equals(Set.of("P1.split", "I1", "R0", "R3", "L1", "PR1", "B1")),
                "checks:only silent breakage fails by default (a split package, an integration leak, an unreal claim, metadata that refuses "
                        + "a claimed version, an unresolved reference, a presentation that draws nothing): " + failing);
        that(Checks.TABLE.get("P3").def() == Checks.Level.WARN && Checks.TABLE.get("P1").def() == Checks.Level.WARN
                && Checks.TABLE.get("I2").def() == Checks.Level.WARN && Checks.TABLE.get("R1").def() == Checks.Level.WARN
                && Checks.TABLE.get("L2").def() == Checks.Level.WARN, "checks:purity text, gates, proofs and unavailable data warn by default");

        Checks.Config c = Checks.decode(List.of(Checks.encodeLevel("P3", Checks.Level.FAIL), Checks.encodeAllowance("P3", "kernel/Rng.java", "seeded by the caller")));
        that(c.level("P3") == Checks.Level.FAIL && c.level("P1") == Checks.Level.WARN, "checks:a configured level wins, the rest keep their default");
        Checks.Findings f = new Checks.Findings(c);
        f.add("P3", "/work/kernel/Rng.java", "uses the clock");
        f.add("P3", "/work/kernel/Other.java", "uses the clock");
        that(f.failing().size() == 1 && f.failing().get(0).site().endsWith("Other.java"), "checks:a fail level fails at a site no allowance covers");
        that(f.all().stream().anyMatch(x -> x.effect().equals("allowed") && "seeded by the caller".equals(x.reason())),
                "checks:an allowed site is recorded with its reason, not dropped");
        Checks.Findings off = new Checks.Findings(Checks.decode(List.of(Checks.encodeLevel("P3", Checks.Level.OFF))));
        off.add("P3", "x", "uses the clock");
        that(off.all().isEmpty(), "checks:an off check records nothing");
        Checks.Findings warn = new Checks.Findings(Checks.Config.DEFAULTS);
        warn.add("P3", "x.java", "uses the clock");
        boolean threw = false;
        try {
            warn.conclude(org.gradle.api.logging.Logging.getLogger(FlexibleSelfTest.class), "x");
        } catch (GradleException e) {
            threw = true;
        }
        that(!threw && warn.warning().size() == 1, "checks:a warn level warns and the build goes on");
        f = new Checks.Findings(c);
        f.add("P3", "kernel/Other.java", "uses the clock");
        threw = false;
        try {
            f.conclude(org.gradle.api.logging.Logging.getLogger(FlexibleSelfTest.class), "the pure layer breaks a source law");
        } catch (GradleException e) {
            threw = e.getMessage().contains("[P3]") && e.getMessage().contains("checks");
        }
        that(threw, "checks:a fail level fails the build, naming the check and where to change it");

        ChecksSpecProbe.expectRefusal("unknown", spec -> spec.level("P99", "warn"));
        ChecksSpecProbe.expectRefusal("level", spec -> spec.level("P3", "loud"));
        ChecksSpecProbe.expectRefusal("reason", spec -> spec.allow("P3", "kernel/Rng.java", " "));
        that(c.toJson().getAsJsonObject("levels").getAsJsonObject("P3").get("default").getAsString().equals("warn")
                        && c.toJson().getAsJsonObject("levels").getAsJsonObject("P3").get("level").getAsString().equals("fail")
                        && c.toJson().getAsJsonArray("allowances").size() == 1,
                "checks:the evidence block carries each level with its default, and every allowance");
    }

    /** A {@link ChecksSpec} on a throwaway project, to prove a bad setting is refused where it is written. */
    static final class ChecksSpecProbe {
        static ChecksSpec spec() {
            Project p = ProjectBuilder.builder().build();
            p.getPluginManager().apply(MyceliumDefaultsPlugin.class);
            return p.getExtensions().getByType(MyceliumDefaultsPlugin.Defaults.class).getChecks();
        }

        static void expectRefusal(String what, java.util.function.Consumer<ChecksSpec> action) {
            boolean refused = false;
            try {
                action.accept(spec());
            } catch (IllegalArgumentException e) {
                refused = true;
            }
            that(refused, "checks:a bad setting is refused where it is written (" + what + ")");
        }
    }

    // --- the era table generates version data --------------------------------------------------------------------------

    static void packMeta(Path root) {
        Eras eras = Eras.load(root.toFile());
        // the formats are the game's own version.json pack_version for the versions with a jar (docs/eras.md, packFormat)
        JsonObject old = pack(eras, "1.8.9");
        that(old.get("pack_format").getAsInt() == 1 && !old.has("supported_formats") && !old.has("min_format"),
                "packMeta:1.8.9 has pack_format 1 alone: " + old);
        that(pack(eras, "1.14.4").get("pack_format").getAsInt() == 4, "packMeta:1.14.4 is format 4");
        JsonObject f192 = pack(eras, "1.19.2");
        that(f192.get("pack_format").getAsInt() == 10 && !f192.has("supported_formats"),
                "packMeta:1.19.2 names the data format (10) in pack_format (assets are 9): " + f192);
        that(pack(eras, "1.20.1").get("pack_format").getAsInt() == 15, "packMeta:1.20.1 is format 15");
        JsonObject f1202 = pack(eras, "1.20.2");
        that(f1202.get("pack_format").getAsInt() == 18 && f1202.getAsJsonArray("supported_formats").size() == 2,
                "packMeta:1.20.2 begins supported_formats: " + f1202);
        JsonObject f1211 = pack(eras, "1.21.1");
        that(f1211.get("pack_format").getAsInt() == 48 && f1211.getAsJsonArray("supported_formats").get(0).getAsInt() == 34
                        && f1211.getAsJsonArray("supported_formats").get(1).getAsInt() == 48 && !f1211.has("min_format"),
                "packMeta:1.21.1 is pack_format 48 with supported_formats [34, 48], as Somnium wrote it by hand: " + f1211);
        JsonObject f1218 = pack(eras, "1.21.8");
        that(f1218.get("pack_format").getAsInt() == 81 && f1218.getAsJsonArray("supported_formats").get(0).getAsInt() == 64,
                "packMeta:1.21.8 is still the pack_format schema: " + f1218);
        JsonObject f1219 = pack(eras, "1.21.9");
        that(f1219.get("min_format").getAsInt() == 69 && f1219.get("max_format").getAsInt() == 88 && !f1219.has("pack_format"),
                "packMeta:1.21.9 is the min_format/max_format schema with no pack_format: " + f1219);
        JsonObject f263 = pack(eras, "26.3");
        that(f263.get("min_format").getAsInt() == 97 && f263.get("max_format").getAsInt() == 121 && !f263.has("pack_format"),
                "packMeta:26.3 is min_format 97, max_format 121, as Somnium wrote it by hand: " + f263);
        that(eras.packFormatFor("1.21.11").resource().toString().equals("75") && eras.packFormatFor("26.3").resource().toString().equals("97.1"),
                "packMeta:a minor is kept in the table (97.1) and a major covers it in the generated range");
        boolean refused = false;
        try {
            eras.packFormatFor("1.5.2");
        } catch (IllegalArgumentException e) {
            refused = e.getMessage().contains("1.6.1");
        }
        that(refused, "packMeta:a version before resource packs is refused, saying where they begin");
        that(eras.packFormatFor("26.3").source().startsWith("jar") && eras.packFormatFor("1.21.6").source().equals("wiki"),
                "packMeta:each row says whether it was read from a game jar or from the wiki");
    }

    private static JsonObject pack(Eras eras, String version) {
        return JsonParser.parseString(eras.packFormatFor(version).mcmeta("A pack")).getAsJsonObject().getAsJsonObject("pack");
    }

    static void overlays() {
        List<String> entries = List.of("|" + new File("base").getAbsolutePath(), "26.1|" + new File("modern").getAbsolutePath());
        that(OverlaySpec.choose(entries, "1.21.1").getName().equals("base"), "overlays:a version before the first entry uses the base");
        that(OverlaySpec.choose(entries, "26.1").getName().equals("modern") && OverlaySpec.choose(entries, "26.3").getName().equals("modern"),
                "overlays:the entry's version and every later one use its directory");
        that(OverlaySpec.choose(List.of("26.1|" + new File("modern").getAbsolutePath()), "1.21.1") == null,
                "overlays:with no base and nothing at or below the version, no directory");
        that(OverlaySpec.choose(List.of("1.21.5|" + new File("a").getAbsolutePath(), "26.1|" + new File("b").getAbsolutePath()), "1.21.11")
                        .getName().equals("a"), "overlays:the newest entry at or below the version wins");
    }

    // --- the tasks, at each level --------------------------------------------------------------------------------------

    static PurityTask purity(Path dir, Path pure, Path platform, List<String> checks) {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        PurityTask t = p.getTasks().register("purity", PurityTask.class).get();
        t.getPureSources().from(pure.toFile());
        t.getPlatformSources().from(platform.toFile());
        t.getPlatformPackages().set(List.of("net.minecraft"));
        t.getChecks().set(checks);
        t.getReportDir().set(dir.resolve("report").toFile());
        return t;
    }

    static boolean runs(Task t) {
        try {
            ((org.gradle.api.internal.AbstractTask) t).getActions().forEach(a -> a.execute(t));
            return true;
        } catch (GradleException e) {
            return false;
        } catch (RuntimeException e) {
            return e.getCause() instanceof GradleException ? false : rethrow(e);
        }
    }

    private static boolean rethrow(RuntimeException e) {
        throw e;
    }

    static void purityLevels(Path root) throws IOException {
        Path dir = Files.createTempDirectory("mycelium-purity-levels-");
        try {
            Path pure = dir.resolve("pure/kernel");
            Path platform = dir.resolve("platform");
            SelfTest.mkdirs(pure);
            SelfTest.mkdirs(platform);
            SelfTest.write(pure.resolve("Clock.java"), "package kernel;\npublic class Clock { long now() { return System.currentTimeMillis(); }\n"
                    + "java.util.SplittableRandom r = null; String s = System.getenv(\"X\"); }\n");
            SelfTest.write(platform.resolve("Mod.java"), "package mod;\npublic class Mod {}\n");
            PurityTask warns = purity(dir, dir.resolve("pure"), platform, List.of());
            that(runs(warns), "purity:P3 warns by default and the build goes on");
            String report = Files.readString(dir.resolve("report/purity.json"), StandardCharsets.UTF_8);
            that(report.contains("\"check\": \"P3\"") && report.contains("\"effect\": \"warn\"") && !report.contains("SplittableRandom"),
                    "purity:the clock and the environment are found, SplittableRandom is no longer banned by text: " + report);
            PurityTask fails = purity(dir, dir.resolve("pure"), platform, List.of(Checks.encodeLevel("P3", Checks.Level.FAIL)));
            that(!runs(fails), "purity:P3 set to fail fails the check");
            PurityTask allowed = purity(dir, dir.resolve("pure"), platform, List.of(Checks.encodeLevel("P3", Checks.Level.FAIL),
                    Checks.encodeAllowance("P3", "kernel/Clock.java", "the harness seeds it")));
            that(runs(allowed), "purity:an allowance for the site lets a fail level pass");
            report = Files.readString(dir.resolve("report/purity.json"), StandardCharsets.UTF_8);
            that(report.contains("\"effect\": \"allowed\"") && report.contains("the harness seeds it"), "purity:the allowance and its reason are in the report: " + report);
            PurityTask off = purity(dir, dir.resolve("pure"), platform, List.of(Checks.encodeLevel("P3", Checks.Level.OFF)));
            that(runs(off), "purity:P3 off passes");
            that(Files.readString(dir.resolve("report/purity.json"), StandardCharsets.UTF_8).contains("\"findings\": []"), "purity:an off check finds nothing");
            // the platform mentioned by the pure layer is P1: a warning (compiling alone is the proof)
            SelfTest.write(pure.resolve("Leak.java"), "package kernel;\npublic class Leak { net.minecraft.World w; }\n");
            that(runs(purity(dir, dir.resolve("pure"), platform, List.of())), "purity:P1 warns by default (the compile-alone proof is the proof)");
            that(!runs(purity(dir, dir.resolve("pure"), platform, List.of(Checks.encodeLevel("P1", Checks.Level.FAIL)))), "purity:P1 set to fail fails");
            // a platform package that merely shares the pure layer's package is the one P1 that stays a failure
            SelfTest.write(platform.resolve("Shared.java"), "package kernel;\npublic class Shared {}\n");
            that(!runs(purity(dir, dir.resolve("pure"), platform, List.of())), "purity:a package split between the layers fails by default (P1.split)");
            // and the artifact's own platform package named from the pure layer is no longer a text rule: compiling alone says so
            SelfTest.delete(platform.resolve("Shared.java"));
            SelfTest.write(pure.resolve("Leak.java"), "package kernel;\npublic class Leak { mod.Mod m; }\n");
            that(runs(purity(dir, dir.resolve("pure"), platform, List.of(Checks.encodeLevel("P3", Checks.Level.OFF)))),
                    "purity:the pure layer reaching the artifact's own platform package is left to the compile-alone proof");
        } finally {
            SelfTest.delete(dir);
        }
    }

    static void isolationGates() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-isolation-");
        try {
            Path src = dir.resolve("src/woflo");
            SelfTest.mkdirs(src.resolve("voice"));
            SelfTest.write(src.resolve("voice/Voice.java"), "package woflo.voice;\nclass Voice { de.vc.Api a; }\n");
            SelfTest.write(src.resolve("Gate.java"), "package woflo;\nclass Gate { woflo.voice.Voice v; }\n");
            SelfTest.write(src.resolve("Other.java"), "package woflo;\nclass Other { woflo.voice.Voice v; }\n");
            IsolationTask derived = isolation(dir, "voice|woflo.voice|de.vc|", List.of());
            that(runs(derived), "isolation:with no declared gates every file that reaches into the home is a derived gate, and I2 passes");
            String report = Files.readString(dir.resolve("report/isolation.json"), StandardCharsets.UTF_8);
            that(report.contains("\"gates\": \"derived\"") && report.contains("woflo/Gate.java") && report.contains("woflo/Other.java"),
                    "isolation:the derived gates are reported: " + report);
            IsolationTask declared = isolation(dir, "voice|woflo.voice|de.vc|woflo/Gate.java", List.of());
            that(runs(declared), "isolation:a declared gate list makes I2 a warning by default");
            report = Files.readString(dir.resolve("report/isolation.json"), StandardCharsets.UTF_8);
            that(report.contains("\"check\": \"I2\"") && report.contains("\"effect\": \"warn\"") && report.contains("Other.java"),
                    "isolation:a reference from outside the declared gates is I2, warned: " + report);
            that(!runs(isolation(dir, "voice|woflo.voice|de.vc|woflo/Gate.java", List.of(Checks.encodeLevel("I2", Checks.Level.FAIL)))),
                    "isolation:I2 set to fail fails");
            that(runs(isolation(dir, "voice|woflo.voice|de.vc|woflo/Gate.java", List.of(Checks.encodeLevel("I2", Checks.Level.FAIL),
                    Checks.encodeAllowance("I2", "woflo/Other.java", "temporary")))), "isolation:an allowance covers the one site");
            // the NoClassDefFoundError law fails by default
            SelfTest.write(src.resolve("Leak.java"), "package woflo;\nclass Leak { de.vc.Api a; }\n");
            that(!runs(isolation(dir, "voice|woflo.voice|de.vc|", List.of())), "isolation:the other mod's API outside its home fails by default (I1)");
            that(runs(isolation(dir, "voice|woflo.voice|de.vc|", List.of(Checks.encodeLevel("I1", Checks.Level.WARN)))), "isolation:I1 can be lowered to warn");
        } finally {
            SelfTest.delete(dir);
        }
    }

    private static IsolationTask isolation(Path dir, String integration, List<String> checks) {
        Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
        IsolationTask t = p.getTasks().register("isolation", IsolationTask.class).get();
        t.getIntegrations().set(List.of(integration));
        t.getSources().from(dir.resolve("src").toFile());
        t.getChecks().set(checks);
        t.getReportDir().set(dir.resolve("report").toFile());
        return t;
    }

    static void usageAndSurface() throws IOException {
        Path dir = Files.createTempDirectory("mycelium-usage-");
        try {
            Path root = Path.of("..").toAbsolutePath().normalize();
            Path src = dir.resolve("src");
            SelfTest.mkdirs(src);
            SelfTest.write(src.resolve("Mod.java"), "package mod;\nclass Mod {}\n");
            for (boolean failOnUnused : new boolean[]{false, true}) {
                Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
                UsageTask t = p.getTasks().register("usage", UsageTask.class).get();
                t.getRoot().set(root.toFile());
                t.getContracts().set(List.of("random.seeded@1"));
                t.getNamespace().set("mod.mycelium");
                t.getFailOnUnused().set(failOnUnused);
                t.getChecks().set(List.of());
                t.getSources().from(src.toFile());
                t.getReportDir().set(dir.resolve("report").toFile());
                that(runs(t) == !failOnUnused, "usage:an unused import warns by default and fails with failOnUnused (U1)");
            }
            Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
            UsageTask t = p.getTasks().register("usageOff", UsageTask.class).get();
            t.getRoot().set(root.toFile());
            t.getContracts().set(List.of("random.seeded@1"));
            t.getNamespace().set("mod.mycelium");
            t.getFailOnUnused().set(false);
            t.getChecks().set(List.of(Checks.encodeLevel("U1", Checks.Level.FAIL)));
            t.getSources().from(src.toFile());
            t.getReportDir().set(dir.resolve("report").toFile());
            that(!runs(t), "usage:U1 set to fail fails");
        } finally {
            SelfTest.delete(dir);
        }
    }

    static void rangeAndLinkageChecks(Path root) {
        // a claim with no keystone: R1 (warn), and the version stays interpolated; an unreal range is R0 (fail); metadata is R3 (fail)
        RangeGate.Result noKeystone = SelfTest.gate(root, SelfTest.claim("fabric", "1.21", "1.21.1", List.of(), List.of()),
                SelfTest.declares("fabric.mod.json", ">=1.21 <=1.21.1"));
        that(noKeystone.findings().size() == 1 && noKeystone.checkOf(noKeystone.findings().get(0)).equals("R1")
                        && noKeystone.report().getAsJsonObject("counts").get("interpolated").getAsInt() == 2,
                "range:no keystone is R1 and leaves both versions interpolated: " + noKeystone.findings());
        RangeGate.Result narrow = SelfTest.gate(root, SelfTest.claim("fabric", "1.21", "1.21.1", List.of("1.21.1"), List.of()),
                SelfTest.declares("fabric.mod.json", ">=1.21.1"));
        that(narrow.findings().stream().allMatch(f -> narrow.checkOf(f).equals("R3")) && !narrow.findings().isEmpty(),
                "range:metadata that refuses a claimed version is R3: " + narrow.findings());
        RangeGate.Result wall = SelfTest.gate(root, SelfTest.claim("fabric", "1.21.4", "1.21.5", List.of("1.21.5"), List.of()),
                SelfTest.declares("fabric.mod.json", ">=1.21.4 <=1.21.5"));
        that(wall.findings().size() == 1 && wall.checkOf(wall.findings().get(0)).equals("R2"), "range:a wall with one side proved is R2: " + wall.findings());
        RangeGate.Result unreal = SelfTest.gate(root, SelfTest.claim("fabric", "1.21", "1.99", List.of("1.21"), List.of()), null);
        that(unreal.findings().stream().allMatch(f -> unreal.checkOf(f).equals("R0")) && !unreal.findings().isEmpty(),
                "range:a claim about a version that does not exist is R0: " + unreal.findings());
        // the same findings through the levels: R1 warns, R3 fails, an allowance for R1 is recorded
        Checks.Findings found = new Checks.Findings(Checks.Config.DEFAULTS);
        noKeystone.findings().forEach(f -> found.add(noKeystone.checkOf(f), "*", f));
        narrow.findings().forEach(f -> found.add(narrow.checkOf(f), "*", f));
        that(!found.warning().isEmpty() && !found.failing().isEmpty(), "range:R1 warns and R3 fails with the defaults");
        Checks.Findings allowed = new Checks.Findings(Checks.decode(List.of(Checks.encodeAllowance("R1", "*", "a new target, not yet run"))));
        noKeystone.findings().forEach(f -> allowed.add(noKeystone.checkOf(f), "*", f));
        that(allowed.failing().isEmpty() && allowed.warning().isEmpty() && allowed.all().get(0).effect().equals("allowed"), "range:an allowance covers R1");
    }

    // --- Mycelium's own proofs -----------------------------------------------------------------------------------------

    static void proofs(Path root) throws IOException {
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        ContractRegistry.Entry seeded = registry.require("random.seeded@1");
        String a = Proofs.fingerprint(root, registry, seeded);
        that(a.equals(Proofs.fingerprint(root, registry, seeded)) && a.matches("[0-9a-f]{64}"), "proofs:a contract's fingerprint is stable");
        that(!a.equals(Proofs.fingerprint(root, registry, registry.require("world.copy@1"))), "proofs:two contracts have two fingerprints");
        Proofs.Recorded recorded = new Proofs.Recorded(List.of(8, 17, 21), java.util.Map.of("random.seeded@1", a));
        that(Proofs.provenUpstream(root, registry, seeded, recorded, List.of(17, 21)), "proofs:unchanged sources on covered Javas are proven upstream");
        that(!Proofs.provenUpstream(root, registry, seeded, recorded, List.of(25)), "proofs:a Java the recorded gate did not run on is not proven");
        that(!Proofs.provenUpstream(root, registry, seeded, new Proofs.Recorded(List.of(21), java.util.Map.of("random.seeded@1", "0".repeat(64))), List.of(21)),
                "proofs:a different fingerprint (changed sources) is not proven: the harness runs");
        that(!Proofs.provenUpstream(root, registry, seeded, Proofs.Recorded.NONE, List.of()), "proofs:no record proves nothing");
        // the same sources with Windows line endings fingerprint the same
        Path copy = Files.createTempDirectory("mycelium-proofs-");
        try {
            SelfTest.copyTree(root.resolve("harness"), copy.resolve("harness"));
            SelfTest.copyTree(root.resolve("contracts/random-seeded"), copy.resolve("contracts/random-seeded"));
            Files.copy(root.resolve("contracts/registry.json"), copy.resolve("contracts/registry.json"));
            ContractRegistry copied = ContractRegistry.parse(Files.readString(copy.resolve("contracts/registry.json"), StandardCharsets.UTF_8), "copy");
            String base = Proofs.fingerprint(copy, copied, copied.require("random.seeded@1"));
            that(base.equals(a), "proofs:the fingerprint does not depend on where the checkout is: " + base + " vs " + a);
            try (var walk = Files.walk(copy.resolve("contracts/random-seeded"))) {
                for (Path p : (Iterable<Path>) walk.filter(x -> x.toString().endsWith(".java"))::iterator) {
                    Files.writeString(p, Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\n", "\r\n"), StandardCharsets.UTF_8);
                }
            }
            that(Proofs.fingerprint(copy, copied, copied.require("random.seeded@1")).equals(base), "proofs:line endings do not change the fingerprint");
            Path some = Files.walk(copy.resolve("contracts/random-seeded")).filter(x -> x.toString().endsWith(".java")).findFirst().orElseThrow();
            Files.writeString(some, Files.readString(some, StandardCharsets.UTF_8) + "\n// changed\n", StandardCharsets.UTF_8);
            that(!Proofs.fingerprint(copy, copied, copied.require("random.seeded@1")).equals(base), "proofs:an edited source changes the fingerprint");
            Proofs.write(copy, recorded);
            Proofs.Recorded back = Proofs.load(copy);
            that(back.eras().equals(recorded.eras()) && back.contracts().equals(recorded.contracts()), "proofs:the record round-trips");
        } finally {
            SelfTest.delete(copy);
        }
        recording(root, registry);
    }

    /**
     * What myceliumRecordProofs may record: only what this run's reports say held, never the registry's whole list. Each way a build
     * is narrowed (-Pmycelium.contracts, -Pmycelium.suites, -x myceliumVerify, -x an era gate) records nothing.
     */
    static void recording(Path root, ContractRegistry registry) throws IOException {
        java.util.function.Supplier<JsonObject> verify = () -> {
            JsonObject o = new JsonObject();
            com.google.gson.JsonArray rs = new com.google.gson.JsonArray();
            try {
                for (ContractRegistry.Entry e : registry.all()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("id", e.id());
                    r.addProperty("status", "pass");
                    r.addProperty("fingerprint", Proofs.fingerprint(root, registry, e));
                    rs.add(r);
                }
            } catch (IOException x) {
                throw new IllegalStateException(x);
            }
            o.add("results", rs);
            return o;
        };
        java.util.function.IntFunction<JsonObject> era = java -> {
            JsonObject o = new JsonObject();
            o.addProperty("java", java);
            o.add("suites", new com.google.gson.JsonArray());
            o.add("skipped", new com.google.gson.JsonArray());
            com.google.gson.JsonArray rs = new com.google.gson.JsonArray();
            for (ContractRegistry.Entry e : registry.all()) {
                JsonObject b = new JsonObject();
                b.addProperty("bundle", e.id());
                b.addProperty("status", "pass");
                b.add("suites", one());
                b.addProperty("suitesTotal", 1);
                rs.add(b);
            }
            o.add("results", rs);
            return o;
        };
        java.util.function.Function<List<Integer>, java.util.Map<Integer, JsonObject>> eras = javas -> {
            java.util.Map<Integer, JsonObject> m = new java.util.LinkedHashMap<>();
            javas.forEach(j -> m.put(j, era.apply(j)));
            return m;
        };
        List<Integer> javas = List.of(8, 17);
        ProofRecordTask.Decision whole = ProofRecordTask.decide(root, registry, verify.get(), eras.apply(javas), javas);
        that(whole.records() && whole.contracts().size() == registry.all().size(),
                "proofs:a run whose every harness and era gate passed records every contract: " + whole.problems());

        JsonObject narrowed = verify.get();
        narrowed.getAsJsonArray("results").remove(0);
        ProofRecordTask.Decision few = ProofRecordTask.decide(root, registry, narrowed, eras.apply(javas), javas);
        that(!few.records() && few.problems().stream().anyMatch(p -> p.contains("-Pmycelium.contracts")),
                "proofs:a run narrowed to some contracts records nothing (it would stamp the rest proven): " + few.problems());

        that(!ProofRecordTask.decide(root, registry, null, eras.apply(javas), javas).records(),
                "proofs:a run without myceliumVerify (-x) records nothing");

        JsonObject failed = verify.get();
        failed.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("status", "fail");
        that(!ProofRecordTask.decide(root, registry, failed, eras.apply(javas), javas).records(),
                "proofs:a contract whose harness did not pass is not recorded");

        JsonObject stale = verify.get();
        stale.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("fingerprint", "0".repeat(64));
        that(!ProofRecordTask.decide(root, registry, stale, eras.apply(javas), javas).records(),
                "proofs:a result for older sources than the ones on disk is not recorded");

        java.util.Map<Integer, JsonObject> suites = eras.apply(javas);
        suites.get(17).add("suites", one());
        that(!ProofRecordTask.decide(root, registry, verify.get(), suites, javas).records(),
                "proofs:an era gate narrowed by -Pmycelium.suites records nothing");

        java.util.Map<Integer, JsonObject> missing = eras.apply(List.of(8));
        that(!ProofRecordTask.decide(root, registry, verify.get(), missing, javas).records(),
                "proofs:an era gate that did not run records nothing");

        java.util.Map<Integer, JsonObject> failing = eras.apply(javas);
        failing.get(8).getAsJsonArray("results").get(0).getAsJsonObject().addProperty("status", "fail");
        that(!ProofRecordTask.decide(root, registry, verify.get(), failing, javas).records(),
                "proofs:a contract an era gate did not pass is not recorded");

        java.util.Map<Integer, JsonObject> partial = eras.apply(javas);
        partial.get(8).getAsJsonArray("results").get(0).getAsJsonObject().addProperty("suitesTotal", 2);
        that(!ProofRecordTask.decide(root, registry, verify.get(), partial, javas).records(),
                "proofs:a bundle whose suites did not all run on an era is not recorded");
    }

    private static com.google.gson.JsonArray one() {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        a.add("x");
        return a;
    }

    // --- the namespace a mod defaults to, and the fingerprints its tasks compare -----------------------------------------------------

    /** Two mods in one group must not share the package everything they import is private to. */
    static void namespaces(Path root, Path tmp) throws IOException {
        Project a = ProjectBuilder.builder().withProjectDir(Files.createDirectories(tmp.resolve("ns-a")).toFile()).withName("dream-mod").build();
        a.setGroup("com.acme");
        a.getPluginManager().apply(JavaPlugin.class);
        a.getPluginManager().apply(MyceliumPlugin.class);
        Project b = ProjectBuilder.builder().withProjectDir(Files.createDirectories(tmp.resolve("ns-b")).toFile()).withName("other-mod-2").build();
        b.setGroup("com.acme");
        b.getPluginManager().apply(JavaPlugin.class);
        b.getPluginManager().apply(MyceliumPlugin.class);
        String na = a.getExtensions().getByType(MyceliumExtension.class).getNamespace().get();
        String nb = b.getExtensions().getByType(MyceliumExtension.class).getNamespace().get();
        that(na.equals("com.acme.dream_mod.mycelium") && nb.equals("com.acme.other_mod_2.mycelium") && !na.equals(nb),
                "namespace:two mods in one group default to two namespaces, from the group and the artifact: " + na + " and " + nb);
        MyceliumExtension explicit = b.getExtensions().getByType(MyceliumExtension.class);
        explicit.getNamespace().set("com.acme.mine");
        that(explicit.getNamespace().get().equals("com.acme.mine"), "namespace:a namespace the mod writes wins over the default");
        Project none = ProjectBuilder.builder().withProjectDir(Files.createDirectories(tmp.resolve("ns-c")).toFile()).withName("3d-thing").build();
        none.getPluginManager().apply(JavaPlugin.class);
        none.getPluginManager().apply(MyceliumPlugin.class);
        that(none.getExtensions().getByType(MyceliumExtension.class).getNamespace().get().equals("mycelium.imported._3d_thing"),
                "namespace:with no group the default is still the artifact's own (a package segment never starts with a digit): "
                        + none.getExtensions().getByType(MyceliumExtension.class).getNamespace().get());
        that(MyceliumPlugin.packageSegment("Some-Mod.id").equals("some_mod_id") && MyceliumPlugin.packageSegment("---").equals("mod"),
                "namespace:an artifact id becomes a package segment");
    }

    static void fingerprints(Path tmp) throws IOException {
        Path lf = Files.createDirectories(tmp.resolve("fp-lf"));
        Path crlf = Files.createDirectories(tmp.resolve("fp-crlf"));
        Files.writeString(lf.resolve("a.json"), "{\n  \"x\": 1\n}\n", StandardCharsets.UTF_8);
        Files.writeString(crlf.resolve("a.json"), "{\r\n  \"x\": 1\r\n}\r\n", StandardCharsets.UTF_8);
        that(Fingerprint.ofDirectory(lf).equals(Fingerprint.ofDirectory(crlf)),
                "fingerprint:a Windows checkout and a Linux one of the same text are the same sources");
        Files.writeString(crlf.resolve("a.json"), "{\r\n  \"x\": 2\r\n}\r\n", StandardCharsets.UTF_8);
        that(!Fingerprint.ofDirectory(lf).equals(Fingerprint.ofDirectory(crlf)), "fingerprint:an edit still changes it");
        Files.write(lf.resolve("b.bin"), new byte[] {13, 10, 1});
        Files.write(crlf.resolve("a.json"), "{\n  \"x\": 1\n}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(crlf.resolve("b.bin"), new byte[] {10, 1});
        that(!Fingerprint.ofDirectory(lf).equals(Fingerprint.ofDirectory(crlf)), "fingerprint:a binary file's bytes are its own");
    }

    // --- the plugin's wiring, on a throwaway project -------------------------------------------------------------------

    static Project mod(Path dir, String name, Project parent, Path root) {
        ProjectBuilder b = ProjectBuilder.builder().withProjectDir(dir.toFile()).withName(name);
        if (parent != null) {
            b.withParent(parent);
        }
        Project p = b.build();
        p.getPluginManager().apply(JavaPlugin.class);
        p.getPluginManager().apply(MyceliumPlugin.class);
        return p;
    }

    private static Set<Task> reachable(Task from) {
        Set<Task> seen = new HashSet<>();
        List<Task> todo = new ArrayList<>(List.of(from));
        while (!todo.isEmpty()) {
            Task t = todo.remove(todo.size() - 1);
            for (Task d : t.getTaskDependencies().getDependencies(t)) {
                if (seen.add(d)) {
                    todo.add(d);
                }
            }
        }
        return seen;
    }

    static void compileIsNeverBlocked(Path root, Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("compile"));
        Project p = mod(dir, "compile", null, root);
        MyceliumExtension ext = p.getExtensions().getByType(MyceliumExtension.class);
        ext.getRoot().set(root.toFile());
        ext.getMinecraft().set("1.21.1");
        ((org.gradle.api.internal.project.ProjectInternal) p).evaluate();
        Set<String> deps = new HashSet<>();
        reachable(p.getTasks().getByName("compileJava")).forEach(t -> deps.add(t.getName()));
        that(!deps.contains("myceliumPurity") && !deps.contains("myceliumIsolation"),
                "compile:compileJava does not depend on the textual checks (docs/decisions/0017): " + deps);
        Set<String> jar = new HashSet<>();
        reachable(p.getTasks().getByName("jar")).forEach(t -> jar.add(t.getName()));
        that(!jar.contains("myceliumPurity") && !jar.contains("myceliumIsolation"), "compile:the jar does not wait on the textual checks: " + jar);
        Set<String> check = new HashSet<>();
        reachable(p.getTasks().getByName("check")).forEach(t -> check.add(t.getName()));
        that(check.contains("myceliumPurity") && check.contains("myceliumIsolation"), "compile:check still runs them: " + check);
    }

    static void derivedContracts(Path root, Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("derive"));
        Path src = Files.createDirectories(dir.resolve("src/main/java/mod"));
        SelfTest.write(src.resolve("Mod.java"), "package mod;\nimport fixture.derive.mycelium.random_seeded.v1.model.SeededRandom;\nclass Mod {}\n");
        SelfTest.write(src.resolve("Prose.java"), "package mod;\n// no contract here: world_copy is only a word\nclass Prose {}\n");
        Project p = mod(dir, "derive", null, root);
        MyceliumExtension ext = p.getExtensions().getByType(MyceliumExtension.class);
        ext.getRoot().set(root.toFile());
        ext.getNamespace().set("fixture.derive.mycelium");
        List<String> ids = MyceliumPlugin.deriveContracts(root.toFile(), p.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets().getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(), ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java").get().getAsFile());
        that(ids.equals(List.of("random.seeded@1")), "derive:the contracts a mod's sources reference under its namespace are its contracts: " + ids);
        // a reference in the generated imports is not a use; a second contract appears when a source names it
        SelfTest.write(src.resolve("More.java"), "package mod;\nclass More { fixture.derive.mycelium.data_catalog.v1.model.Catalog c; }\n");
        ids = MyceliumPlugin.deriveContracts(root.toFile(), p.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets().getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(), ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java").get().getAsFile());
        that(ids.contains("random.seeded@1") && ids.contains("data.catalog@1") && ids.size() == 2, "derive:a new reference adds its contract: " + ids);
        // v1 does not match v10, and another namespace is not this mod's
        SelfTest.write(src.resolve("More.java"), "package mod;\nclass More { other.ns.random_seeded.v1.X x; fixture.derive.mycelium.random_seeded.v10.Y y; }\n");
        SelfTest.delete(src.resolve("Mod.java"));
        ids = MyceliumPlugin.deriveContracts(root.toFile(), p.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets().getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(), ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java").get().getAsFile());
        that(ids.isEmpty(), "derive:another namespace and a longer version number are not references: " + ids);
        // only code counts: a reference in a comment (a branch a Stonecutter swap switched off) is not a use, but one in a string is kept whole
        that(MyceliumPlugin.withoutComments("a // x.y\nb /* x.y */ c \"//not a comment\" 'q' \"\\\"/*\" d\n").equals("a \nb   c \"//not a comment\" 'q' \"\\\"/*\" d\n"),
                "derive:comments are dropped and literals kept");
        SelfTest.write(src.resolve("More.java"), "package mod;\n// fixture.derive.mycelium.data_catalog.v1.model.Catalog\n/* fixture.derive.mycelium.data_catalog.v1.model.X */\nclass More { }\n");
        ids = MyceliumPlugin.deriveContracts(root.toFile(), p.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class).getSourceSets().getByName("main").getJava().getSrcDirs(), ext.getPure().getSources().getFiles(), ext.getNamespace().get(), p.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java").get().getAsFile());
        that(ids.isEmpty(), "derive:a reference only in a comment is not a use: " + ids);
        // explicit contracts(...) stay an override; the evidence says which it was
        ext.contracts("world.copy@1");
        that(ext.getContracts().get().equals(List.of("world.copy@1")), "derive:an explicit contracts(...) is kept as the override");
    }

    static void defaultsInherited(Path root, Path tmp) throws Exception {
        Project top = ProjectBuilder.builder().withProjectDir(Files.createDirectories(tmp.resolve("top")).toFile()).withName("top").build();
        top.getPluginManager().apply(MyceliumDefaultsPlugin.class);
        MyceliumExtension d = top.getExtensions().getByType(MyceliumDefaultsPlugin.Defaults.class);
        d.getRoot().set(root.toFile());
        d.getNamespace().set("fixture.defaults.mycelium");
        d.integration("voice", "fixture.voice", "de.vc");
        d.checks(c -> {
            c.warn("I1");
            c.allow("P3", "kernel/Rng.java", "seeded by the caller");
        });
        d.getFailOnUnused().set(true);
        d.getPure().getRelease().set(21);
        d.getPure().getSources().from(Files.createDirectories(tmp.resolve("top/kernels")).toFile());
        d.getArtifactId().set("fixture");
        d.packMeta(m -> m.getDescription().set("Fixture resources"));
        d.overlays(o -> o.base(tmp.resolve("res-base").toFile()));
        Project a = mod(Files.createDirectories(tmp.resolve("top/a")), "a", top, root);
        Project b = mod(Files.createDirectories(tmp.resolve("top/b")), "b", top, root);
        MyceliumExtension ea = a.getExtensions().getByType(MyceliumExtension.class);
        MyceliumExtension eb = b.getExtensions().getByType(MyceliumExtension.class);
        ea.getMinecraft().set("1.21.1");
        eb.getMinecraft().set("26.3");
        eb.getNamespace().set("fixture.b.own");
        eb.getFailOnUnused().set(false);
        that(ea.getRoot().get().getAsFile().equals(root.toFile()) && eb.getRoot().get().getAsFile().equals(root.toFile()),
                "defaults:the Mycelium root is written once and inherited by every project");
        that(ea.getNamespace().get().equals("fixture.defaults.mycelium") && eb.getNamespace().get().equals("fixture.b.own"),
                "defaults:a project inherits the namespace, and its own setting wins");
        that(ea.getFailOnUnused().get() && !eb.getFailOnUnused().get(), "defaults:a scalar is inherited and overridable");
        that(ea.getPure().getRelease().get() == 21 && eb.getPure().getRelease().get() == 21, "defaults:the pure layer's release is inherited");
        that(ea.getPure().getSources().getFiles().size() == 1 && eb.getPure().getSources().getFiles().size() == 1,
                "defaults:the pure sources are inherited");
        that(ea.getIntegrations().get().size() == 1 && eb.getIntegrations().get().equals(ea.getIntegrations().get()),
                "defaults:integrations are declared once");
        that(ea.getArtifactId().get().equals("fixture") && ea.getTargetProfile().get().equals("a") && eb.getTargetProfile().get().equals("b"),
                "defaults:what only a project knows (its name) stays the project's, what the root names is inherited");
        eb.checks(c -> c.fail("I1"));
        List<String> merged = eb.getChecks().merged(d.getChecks());
        Checks.Config config = Checks.decode(merged);
        that(config.level("I1") == Checks.Level.FAIL && Checks.decode(ea.getChecks().merged(d.getChecks())).level("I1") == Checks.Level.WARN
                        && config.allowed("P3", "x/kernel/Rng.java") != null,
                "defaults:check levels merge (the project's after the default's) and allowances are inherited: " + merged);
        that(a.getTasks().getByName("myceliumPackMeta") != null && ea.getPackMeta().isOn() && eb.getPackMeta().isOn()
                        && ea.getPackMeta().getDescription().get().equals("Fixture resources"), "defaults:the pack.mcmeta request is inherited");
        // mixin Java and the range, from the era table
        that(ea.getMixinJava().equals("JAVA_21") && eb.getMixinJava().equals("JAVA_25") && eb.mixinJava(21).equals("JAVA_21"),
                "mixinJava:the target's Java as a mixin level, capped for a Mixin that stops lower: " + ea.getMixinJava() + " " + eb.getMixinJava());
        ea.getMinecraft().set("1.12.2");
        that(ea.getMixinJava().equals("JAVA_8"), "mixinJava:Java 8 targets get JAVA_8");
        ea.getMinecraft().set("1.21.1");
        String range = ea.minecraftRange("fabric").get();
        that(range.contains("1.21.1") && range.contains("26.3"), "minecraftRange:the versions the build has, as the loader writes a range: " + range);
        // a project that writes its own list replaces the default one
        ea.integration("cobblemon", "fixture.cobblemon", "com.cobblemon");
        that(ea.getIntegrations().get().size() == 1 && ea.getIntegrations().get().get(0).startsWith("cobblemon|"), "defaults:a list a project writes replaces the default list: " + ea.getIntegrations().get());
    }
}
