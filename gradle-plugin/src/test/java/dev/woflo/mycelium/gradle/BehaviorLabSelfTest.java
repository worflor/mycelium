package dev.woflo.mycelium.gradle;

import com.google.gson.JsonObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Exercises the lab mutator only against a tiny generated fixture. */
public final class BehaviorLabSelfTest {
    private BehaviorLabSelfTest() {}

    public static void run() throws Exception {
        Path root = Files.createTempDirectory("mycelium-behavior-lab-test-");
        try {
            offlineReportChecks(root);
            Path sources = Files.createDirectories(root.resolve("src"));
            Path fixtureClasses = Files.createDirectories(root.resolve("fixture-classes"));
            Path boundaryClasses = Files.createDirectories(root.resolve("trusted-boundary"));
            Path fixtureSource = sources.resolve("Fixture.java");
            Path boundarySource = Files.createDirectories(sources.resolve("mycelium/lab")).resolve("Boundary.java");
            Files.writeString(fixtureSource, "public class Fixture { public static final Object bound = new Object(); }\n", StandardCharsets.UTF_8);
            Files.writeString(boundarySource, "package mycelium.lab; public final class Boundary { public static void register(Runnable r) { r.run(); } }\n", StandardCharsets.UTF_8);
            var compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) throw new AssertionError("A JDK compiler is required for the generated Java 8 fixture");
            int fixtureCompiled = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", fixtureClasses.toString(), fixtureSource.toString());
            int boundaryCompiled = compiler.run(null, null, null, "--release", "8", "-Xlint:-options", "-d", boundaryClasses.toString(), boundarySource.toString());
            check(fixtureCompiled == 0 && boundaryCompiled == 0, "generated Java 8 fixture and trusted boundary compile independently");

            Path original = root.resolve("fixture.jar");
            byte[] originalClass = Files.readAllBytes(fixtureClasses.resolve("Fixture.class"));
            Manifest signedLikeManifest = new Manifest();
            signedLikeManifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            signedLikeManifest.getMainAttributes().putValue("SHA-256-Digest-Manifest", "fixture-digest");
            var section = new Attributes(); section.putValue("SHA-256-Digest", "fixture-digest");
            signedLikeManifest.getEntries().put("Fixture.class", section);
            try (var jar = new JarOutputStream(Files.newOutputStream(original), signedLikeManifest)) {
                jar.putNextEntry(new JarEntry("Fixture.class")); jar.write(originalClass); jar.closeEntry();
            }
            String originalHash = BehaviorLab.digest(original, "SHA-512");
            var first = BehaviorLab.mutate(original, 8, 39217L, root.resolve("first"));
            var second = BehaviorLab.mutate(original, 8, 39217L, root.resolve("second"));
            check(first != null && second != null, "mutator selects the fixture's bound field");
            check(first.slot().owner().equals("Fixture") && first.slot().name().equals("bound"), "mutator selects the expected binding");
            check(BehaviorLab.digest(first.control(), "SHA-512").equals(BehaviorLab.digest(second.control(), "SHA-512")), "control output is deterministic");
            check(BehaviorLab.digest(first.mutant(), "SHA-512").equals(BehaviorLab.digest(second.mutant(), "SHA-512")), "mutant output is deterministic");
            try (var control = new java.util.jar.JarFile(first.control().toFile())) {
                var rewrittenManifest = control.getManifest();
                check(rewrittenManifest != null && rewrittenManifest.getMainAttributes().getValue("SHA-256-Digest-Manifest") == null
                        && !rewrittenManifest.getEntries().containsKey("Fixture.class"), "rewritten manifest drops stale signature digests");
            }
            check(originalHash.equals(BehaviorLab.digest(original, "SHA-512"))
                    && java.util.Arrays.equals(originalClass, Files.readAllBytes(fixtureClasses.resolve("Fixture.class"))), "original archive and class bytes stay unchanged");

            JsonObject controlAnalysis = analyze(first.control());
            JsonObject mutantAnalysis = analyze(first.mutant());
            int controlCandidates = BehaviorLab.candidates(controlAnalysis, first.slot());
            int mutantCandidates = BehaviorLab.candidates(mutantAnalysis, first.slot());
            check(controlCandidates == 0 && mutantCandidates == 1,
                    "analyzer reports C1=0 on control and C1=1 on mutant (got " + controlCandidates + " and " + mutantCandidates + ")");
            JsonObject plan = new JsonObject(), artifact = new JsonObject();
            plan.addProperty("schema", 1);
            artifact.addProperty("path", original.toString());
            artifact.addProperty("filename", original.getFileName().toString());
            artifact.addProperty("sha512", originalHash);
            artifact.addProperty("size", Files.size(original));
            artifact.addProperty("target_java", 8);
            var artifacts = new com.google.gson.JsonArray(); artifacts.add(artifact); plan.add("artifacts", artifacts);
            Path planFile = root.resolve("plan.json"), scanned = root.resolve("scanned");
            BehaviorLab.write(planFile, plan);
            BehaviorLab.scan(planFile, scanned);
            var receipt = com.google.gson.JsonParser.parseString(Files.readString(scanned.resolve("report.json"))).getAsJsonObject();
            check(receipt.get("complete").getAsBoolean() && receipt.get("failures").getAsInt() == 0,
                    "generated corpus completes its full worker and mutation pipeline");
            var recorded = receipt.getAsJsonArray("artifacts").get(0).getAsJsonObject();
            for (String outcome : java.util.List.of("baseline", "control", "mutant"))
                check(recorded.getAsJsonObject(outcome).get("fullReportSha256").getAsString()
                                .equals(BehaviorLab.digest(scanned.resolve("000/" + outcome + ".json"), "SHA-256")),
                        "aggregate receipt binds the " + outcome + " worker report");

            // Boundary is parent-loaded from its own directory, which is never put in either tested jar.
            try (var trusted = new URLClassLoader(new URL[]{boundaryClasses.toUri().toURL()}, BehaviorLabSelfTest.class.getClassLoader())) {
                runEntry(first.control(), trusted, false);
                runEntry(first.mutant(), trusted, true);
            }
            System.out.println("BehaviorLabSelfTest: generated Java 8 binding mutation, isolated eager callback, immutability, and reproducibility passed.");
        } finally {
            deleteTree(root);
        }
    }

    private static void offlineReportChecks(Path root) throws Exception {
        Path reportFile = root.resolve("synthetic-worker.json");
        JsonObject synthetic = new JsonObject();
        synthetic.addProperty("complete", false);
        synthetic.addProperty("analyzedBodies", 0);
        var errors = new com.google.gson.JsonArray(); errors.add("synthetic analysis error"); synthetic.add("analysisErrors", errors);
        var roots = new com.google.gson.JsonArray(); roots.add(new JsonObject()); roots.add(new JsonObject()); synthetic.add("roots", roots);
        var dependencies = new com.google.gson.JsonArray(); dependencies.add(new JsonObject()); dependencies.add(new JsonObject()); dependencies.add(new JsonObject());
        synthetic.add("dependencies", dependencies);
        var findings = new com.google.gson.JsonArray();
        findings.add(finding(BehaviorLab.PREFIX + "entry", "bound"));
        findings.add(finding("ordinaryRoot", "ignoredField"));
        findings.add(finding(BehaviorLab.PREFIX + "entry", "otherField"));
        synthetic.add("findings", findings);
        BehaviorLab.write(reportFile, synthetic);
        JsonObject summary = BehaviorLab.workerSummary(reportFile);
        check(summary.get("fullReportSha256").getAsString().equals(BehaviorLab.digest(reportFile, "SHA-256")),
                "worker receipt binds its full report bytes");
        Path implementation = Files.createDirectories(root.resolve("implementation/dev/woflo/mycelium/gradle"));
        Files.writeString(implementation.resolve("CallbackInitialization.class"), "outer");
        Files.writeString(implementation.resolve("CallbackInitialization$Summary.class"), "nested");
        String originalImplementation = BehaviorLab.analyzerFingerprint(root.resolve("implementation"));
        Files.writeString(implementation.resolve("Unrelated.class"), "unrelated");
        check(originalImplementation.equals(BehaviorLab.analyzerFingerprint(root.resolve("implementation"))),
                "analyzer fingerprint excludes unrelated classes");
        Files.writeString(implementation.resolve("CallbackInitialization$Summary.class"), "changed");
        check(!originalImplementation.equals(BehaviorLab.analyzerFingerprint(root.resolve("implementation"))),
                "analyzer fingerprint includes nested domain implementations");
        check(summary.get("rootsCount").getAsInt() == 2 && summary.get("dependenciesCount").getAsInt() == 3,
                "worker summary counts roots and dependencies while discarding their graphs");
        check(summary.getAsJsonArray("findings").size() == 2
                        && summary.getAsJsonArray("findings").get(0).getAsJsonObject().getAsJsonObject("dependency").get("name").getAsString().equals("bound"),
                "worker summary retains injected entry witnesses and drops unrelated roots");
        check(!summary.get("complete").getAsBoolean() && summary.getAsJsonArray("analysisErrors").get(0).getAsString().equals("synthetic analysis error"),
                "worker summary preserves incomplete and error metadata");

        Path staleInput = root.resolve("not-a-jar.bin");
        Files.writeString(staleInput, "invalid jar", StandardCharsets.UTF_8);
        Path staleOutput = root.resolve("stale-worker.json");
        JsonObject staleSuccess = new JsonObject(); staleSuccess.addProperty("complete", true); staleSuccess.addProperty("stale", true);
        BehaviorLab.write(staleOutput, staleSuccess);
        JsonObject failedLaunch = BehaviorLab.worker(BehaviorLab.launcher(root.resolve("worker-launcher.jar")), staleInput, 8, staleOutput);
        check(!failedLaunch.get("complete").getAsBoolean() && failedLaunch.has("error") && !failedLaunch.has("stale"),
                "failed worker launch replaces a stale successful report");
    }

    private static JsonObject finding(String rootName, String dependencyName) {
        JsonObject finding = new JsonObject();
        JsonObject root = new JsonObject(); root.addProperty("owner", "Fixture"); root.addProperty("name", rootName); finding.add("root", root);
        JsonObject dependency = new JsonObject(); dependency.addProperty("owner", "Fixture"); dependency.addProperty("name", dependencyName); finding.add("dependency", dependency);
        return finding;
    }

    private static JsonObject analyze(Path jar) throws Exception {
        var result = CallbackInitialization.inspect(java.util.List.of(jar), 8);
        return new com.google.gson.GsonBuilder().create().toJsonTree(result).getAsJsonObject();
    }

    private static void runEntry(Path jar, ClassLoader trusted, boolean expectNpe) throws Exception {
        try (var isolated = new URLClassLoader(new URL[]{jar.toUri().toURL()}, trusted)) {
            try {
                isolated.loadClass("Fixture").getMethod(BehaviorLab.PREFIX + "entry").invoke(null);
                check(!expectNpe, "mutant callback should fail on the cleared final binding");
            } catch (java.lang.reflect.InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                check(expectNpe && cause instanceof NullPointerException,
                        "expected " + (expectNpe ? "NPE" : "successful callback") + ", got " + cause);
            }
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
