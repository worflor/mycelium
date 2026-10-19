package dev.woflo.mycelium.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.tooling.events.task.TaskFinishEvent;
import org.gradle.tooling.events.task.TaskFailureResult;
import org.gradle.tooling.events.task.TaskOperationDescriptor;
import org.gradle.tooling.events.task.TaskSkippedResult;
import org.gradle.tooling.events.task.TaskSuccessResult;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.woflo.mycelium.gradle.SelfTest.that;

/** Exercises build history, skipped producers, configuration caching and the artifact/report boundary. */
public final class EvidenceSelfTest {
    public static void main(String[] args) {
        try {
            run(Path.of(args[0]));
            SelfTest.finish("Evidence self-test");
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(2);
        }
    }

    static void run(Path root) throws Exception {
        outcomes();
        subjects();
        consumer(root);
    }

    static void outcomes() throws Exception {
        TaskOutcomes outcomes = new TaskOutcomes() {
            @Override public org.gradle.api.services.BuildServiceParameters.None getParameters() { return null; }
        };
        that(!TaskOutcomes.CURRENT.equals(outcomes.states(Map.of("gate", ":missing"), List.of("test")).get("gate")),
                "evidence: an excluded or unobserved producer cannot revive a prior report");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var waiting = executor.submit(() -> outcomes.states(Map.of("gate", ":gate"), List.of()));
            outcomes.onFinish(event(":gate", TaskSuccessResult.class));
            that(TaskOutcomes.CURRENT.equals(waiting.get(5, TimeUnit.SECONDS).get("gate")),
                    "evidence: asynchronous completion facts reach dependent tasks");
            outcomes.onFinish(event(":skipped", TaskSkippedResult.class));
            that(outcomes.states(Map.of("gate", ":skipped"), List.of()).get("gate").equals("skipped"),
                    "evidence: a skipped producer is distinct from a validated up-to-date success");
            outcomes.onFinish(event(":failed", TaskFailureResult.class));
            that(outcomes.states(Map.of("gate", ":failed"), List.of()).get("gate").equals("failed"),
                    "evidence: a failed producer cannot establish a current report");
            that(TaskOutcomes.CURRENT.equals(outcomes.states(Map.of("alias", ":gate"), List.of()).get("alias")),
                    "evidence: multiple consumers can observe one producer without consuming its fact");
            that(new ArrayList<>(outcomes.states(Map.of("z", ":gate", "a", ":gate"), List.of()).keySet()).equals(List.of("a", "z")),
                    "evidence: producer facts have stable key order across build JVMs");
        } finally { executor.shutdownNow(); }
    }

    static TaskFinishEvent event(String path, Class<?> resultType) {
        var descriptor = Proxy.newProxyInstance(EvidenceSelfTest.class.getClassLoader(), new Class<?>[]{TaskOperationDescriptor.class},
                (proxy, method, args) -> method.getName().equals("getTaskPath") ? path : null);
        var result = Proxy.newProxyInstance(EvidenceSelfTest.class.getClassLoader(), new Class<?>[]{resultType},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
        return (TaskFinishEvent) Proxy.newProxyInstance(EvidenceSelfTest.class.getClassLoader(), new Class<?>[]{TaskFinishEvent.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getDescriptor" -> descriptor;
                    case "getResult" -> result;
                    default -> null;
                });
    }

    static void subjects() throws Exception {
        Path dir = Files.createTempDirectory("mycelium-evidence-subjects-");
        try {
            Path bytes = dir.resolve("archive.jar"), report = dir.resolve("linkage.json"), embedded = dir.resolve("embedded.json");
            Files.writeString(bytes, "abc");
            that(Fingerprint.ofFile(bytes).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                    "evidence: artifact hashes cover raw bytes, without a filename prefix");
            Files.writeString(report, "{\"artifact\":{\"sha256\":\"" + Fingerprint.ofFile(bytes) + "\"},\"checked\":false}");
            Files.writeString(embedded, "{\"schema\":4}");
            Project p = ProjectBuilder.builder().withProjectDir(dir.toFile()).build();
            VerificationEvidenceTask task = p.getTasks().create("verificationEvidence", VerificationEvidenceTask.class);
            var subject = spec(p, bytes);
            var result = spec(p, report);
            result.getSubject().set("remapped"); result.getSubjectHashPath().set("artifact.sha256");
            task.getArtifacts().put("remapped", subject);
            task.getReports().put("linkage", result);
            task.getEmbeddedEvidence().set(embedded.toFile());
            task.getProducerPaths().set(Map.of());
            task.getProducerStates().set(Map.of("embedded", TaskOutcomes.CURRENT, "artifact/remapped", TaskOutcomes.CURRENT,
                    "report/linkage", TaskOutcomes.CURRENT));
            task.getReportFile().set(dir.resolve("verification.json").toFile());
            task.run();
            JsonObject evidence = read(task.getReportFile().get().getAsFile().toPath());
            that(evidence.getAsJsonObject("reports").getAsJsonObject("linkage").get("subject").getAsString().equals("remapped")
                            && !evidence.getAsJsonObject("reports").getAsJsonObject("linkage").getAsJsonObject("result").get("checked").getAsBoolean(),
                    "evidence: reports retain their scope and bind to their own archive subject");
            that(!evidence.get("artifactBound").getAsBoolean(),
                    "evidence: inspecting an archive does not establish a current shipping artifact");
            task.getProducerStates().put("report/linkage", "skipped"); task.run();
            that(!read(task.getReportFile().get().getAsFile().toPath()).getAsJsonObject("reports").getAsJsonObject("linkage")
                            .get("available").getAsBoolean(), "evidence: skipped producers do not promote existing files");
            task.getProducerStates().put("report/linkage", TaskOutcomes.CURRENT);
            Files.writeString(bytes, "changed");
            boolean rejected = false;
            try { task.run(); } catch (java.io.IOException expected) { rejected = expected.getMessage().contains("does not describe subject"); }
            that(rejected, "evidence: a report about different archive bytes is rejected");
            that(!Files.exists(task.getReportFile().get().getAsFile().toPath()),
                    "evidence: a failed aggregation removes its previous success report");
            task.getReports().set(Map.of());
            subject.getShippingArtifact().set(true);
            for (String manifest : List.of("{\"schema\":3}", "{\"schema\":4}", "absent")) {
                try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(bytes))) {
                    if (!manifest.equals("absent")) {
                        zip.putNextEntry(new java.util.zip.ZipEntry("META-INF/mycelium/evidence.json"));
                        zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                        zip.closeEntry();
                    }
                }
                task.run();
                that(read(task.getReportFile().get().getAsFile().toPath()).get("artifactBound").getAsBoolean()
                                == manifest.equals("{\"schema\":4}"),
                        "evidence: shipping binding compares the actual embedded manifest: " + manifest);
            }
        } finally { SelfTest.delete(dir); }
    }

    static VerificationEvidenceTask.Report spec(Project p, Path file) {
        var spec = p.getObjects().newInstance(VerificationEvidenceTask.Report.class);
        spec.getEnabled().set(true); spec.getFiles().from(file.toFile()); spec.getSubject().set(""); spec.getSubjectHashPath().set("");
        spec.getShippingArtifact().set(false);
        return spec;
    }

    static void consumer(Path root) throws Exception {
        Path fixture = Files.createTempDirectory("mycelium-evidence-consumer-");
        boolean complete = false;
        try {
            Properties metadata = new Properties();
            try (var stream = Files.newInputStream(root.resolve("gradle-plugin/build/pluginUnderTestMetadata/plugin-under-test-metadata.properties"))) {
                metadata.load(stream);
            }
            String classpath = java.util.Arrays.stream(metadata.getProperty("implementation-classpath").split(java.util.regex.Pattern.quote(File.pathSeparator)))
                    .map(EvidenceSelfTest::quote).collect(java.util.stream.Collectors.joining(","));
            write(fixture.resolve("settings.gradle"), "rootProject.name='evidence-fixture'\n");
            write(fixture.resolve("build.gradle"), """
                    buildscript { dependencies { classpath files(%s) } }
                    apply plugin: 'java'
                    apply plugin: 'dev.woflo.mycelium'
                    mycelium {
                        root = file(%s)
                        namespace = 'fixture.evidence'
                        contracts('random.seeded@1')
                        verify = !providers.gradleProperty('noVerify').isPresent()
                        pure { sources.from(file('kernel')); release = 17 }
                        eras = [%d]
                        if (providers.gradleProperty('surfaceOff').isPresent()) checks { off('S1') }
                    }
                    if (providers.gradleProperty('custom').isPresent()) {
                        tasks.register('shippingJar', Jar) { dependsOn tasks.check; from sourceSets.main.output; archiveClassifier='shipping' }
                        mycelium { artifactJars = ['shippingJar'] }
                    }
                    """.formatted(classpath, quote(root.toAbsolutePath().toString()), Runtime.version().feature()));
            Path source = fixture.resolve("src/main/java/fixture/Mod.java"), kernel = fixture.resolve("kernel/fixture/kernel/Kernel.java");
            write(source, "package fixture; public class Mod { fixture.evidence.random_seeded.v1.model.SeededRandom random; }");
            write(kernel, "package fixture.kernel; public class Kernel { public static long value() { return 1; } }");
            gradle(root, fixture, "check");
            JsonObject first = embedded(fixture);
            that(first.getAsJsonArray("contracts").get(0).getAsJsonObject().getAsJsonObject("usage").get("referenced").getAsBoolean(),
                    "evidence integration: baseline referenced contract reaches the jar");
            verifyBound(fixture);
            String repeat = gradle(root, fixture, "check", "--info");
            if (!repeat.contains("myceliumVerificationEvidence UP-TO-DATE")) {
                System.err.println(repeat);
            }
            that(repeat.contains("Configuration cache entry reused") && repeat.contains("myceliumVerificationEvidence UP-TO-DATE"),
                    "evidence integration: completion facts and report inputs support configuration cache and up-to-date reuse");
            write(source, "package fixture; public class Mod {}");
            write(kernel, "package fixture.kernel; public class Kernel { public static long value() { return System.nanoTime(); } }");
            gradle(root, fixture, "jar");
            JsonObject changed = embedded(fixture);
            that(!changed.getAsJsonArray("contracts").get(0).getAsJsonObject().getAsJsonObject("usage").get("referenced").getAsBoolean(),
                    "evidence integration: a changed usage report invalidates artifact evidence");
            that(!changed.getAsJsonObject("checks").getAsJsonObject("reports").getAsJsonObject("purity").get("checked").getAsBoolean(),
                    "evidence integration: jar does not reuse optional check results from an earlier check");
            gradle(root, fixture, "jar", "-PnoVerify");
            that(!embedded(fixture).getAsJsonArray("contracts").get(0).getAsJsonObject().getAsJsonObject("harness").get("checked").getAsBoolean(),
                    "evidence integration: verify=false cannot promote a historical harness report");
            gradle(root, fixture, "check", "-PsurfaceOff");
            verifyBound(fixture);
            JsonObject checked = read(fixture.resolve("build/mycelium/verification/evidence.json"));
            that(!checked.getAsJsonObject("reports").getAsJsonObject("surface").get("available").getAsBoolean(),
                    "evidence integration: an S1-off skip does not revive an earlier surface report");
            that(checked.getAsJsonObject("reports").getAsJsonObject("purity").getAsJsonObject("result")
                            .equals(read(fixture.resolve("build/mycelium/purity/purity.json"))),
                    "evidence integration: verification evidence carries the current producer result");
            gradle(root, fixture, "myceliumVerificationEvidence", "-x", "myceliumPurity");
            that(!read(fixture.resolve("build/mycelium/verification/evidence.json")).getAsJsonObject("reports").getAsJsonObject("purity")
                            .get("available").getAsBoolean(), "evidence integration: an excluded producer leaves no successful check claim");
            gradle(root, fixture, "check", "-x", "jar");
            that(!read(fixture.resolve("build/mycelium/verification/evidence.json")).get("artifactBound").getAsBoolean(),
                    "evidence integration: excluding the shipping producer cannot validate an old jar");
            gradle(root, fixture, "jar", "-x", "myceliumEvidence");
            try (var jar = new java.util.zip.ZipFile(fixture.resolve("build/libs/evidence-fixture.jar").toFile())) {
                that(jar.getEntry("META-INF/mycelium/evidence.json") == null,
                        "evidence integration: excluding the manifest producer cannot embed its stale output");
            }
            Files.delete(fixture.resolve("build/mycelium/evidence/evidence.json"));
            gradle(root, fixture, "myceliumVerificationEvidence", "-x", "myceliumEvidence");
            JsonObject absent = read(fixture.resolve("build/mycelium/verification/evidence.json"));
            that(!absent.get("artifactBound").getAsBoolean()
                            && !absent.getAsJsonObject("artifactEvidence").get("checked").getAsBoolean(),
                    "evidence integration: an absent excluded manifest is recorded as unavailable");
            gradle(root, fixture, "check", "-Pcustom");
            verifyBound(fixture);
            Path composite = Files.createDirectories(fixture.resolve("composite"));
            write(composite.resolve("settings.gradle"), "rootProject.name='evidence-host'\nincludeBuild('..') { name='consumer' }\n");
            gradle(root, composite, ":consumer:check");
            verifyBound(fixture);
            JsonObject compositeEvidence = read(fixture.resolve("build/mycelium/verification/evidence.json"));
            that(compositeEvidence.getAsJsonObject("producers").getAsJsonObject("embedded").get("task").getAsString()
                            .equals(":consumer:myceliumEvidence"),
                    "evidence integration: completion identities include the owning build in a composite");
            complete = true;
        } finally {
            if (complete) SelfTest.delete(fixture);
            else System.err.println("Evidence consumer and Gradle logs retained at " + fixture);
        }
    }

    static void verifyBound(Path fixture) throws Exception {
        JsonObject evidence = read(fixture.resolve("build/mycelium/verification/evidence.json"));
        boolean bound = evidence.get("artifactBound").getAsBoolean();
        for (var entry : evidence.getAsJsonArray("artifacts")) {
            JsonObject artifact = entry.getAsJsonObject();
            bound &= artifact.get("sha256").getAsString().equals(Fingerprint.ofFile(fixture.resolve("build/libs").resolve(artifact.get("name").getAsString())));
        }
        that(bound, "evidence integration: the sidecar describes exact finished archive bytes");
    }

    static JsonObject embedded(Path fixture) throws Exception {
        try (var jar = new java.util.zip.ZipFile(fixture.resolve("build/libs/evidence-fixture.jar").toFile())) {
            var entry = jar.getEntry("META-INF/mycelium/evidence.json");
            if (entry == null) throw new AssertionError("No artifact evidence in jar");
            return JsonParser.parseString(new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    static String gradle(Path root, Path fixture, String... tasks) throws Exception {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-classpath", root.resolve("gradle/wrapper/gradle-wrapper.jar").toString(), "org.gradle.wrapper.GradleWrapperMain",
                "--project-dir", fixture.toString(), "--configuration-cache", "--no-daemon", "--max-workers=1",
                "-Dorg.gradle.jvmargs=-Xmx512m", "--console=plain"));
        command.addAll(List.of(tasks));
        Path log = Files.createTempFile(fixture, "gradle-", ".log");
        Process process = new ProcessBuilder(command).directory(fixture.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        int exit = process.waitFor();
        String output = Files.readString(log);
        if (exit != 0) throw new AssertionError("Consumer failed: " + List.of(tasks) + "\n" + output);
        return output;
    }

    static String quote(String value) { return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"; }
    static JsonObject read(Path file) throws Exception { return JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
    static void write(Path file, String value) throws Exception { Files.createDirectories(file.getParent()); Files.writeString(file, value); }
}
