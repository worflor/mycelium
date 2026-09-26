package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One era's gate (docs/eras.md). Every bundle (each contract harness, the artifact's pure layer and its suites) must
 * hold on this era's Java, a real JVM of that version (a Gradle toolchain, provisioned if absent):
 * <ol>
 *   <li><b>Stubs:</b> bytecode newer than the era is downgraded by JvmDowngrader, and nothing may be left that it could
 *   not backport (each would throw {@code MissingStubError} at the first call).</li>
 *   <li><b>P2 linkage:</b> every class, method and field the bundle reaches outside itself exists on this JVM, whether or
 *   not a suite reaches the call.</li>
 *   <li><b>Behaviour:</b> every suite main exits 0 on this JVM (a contract's reference is clean and its rejections are
 *   caught; a kernel's suite passes).</li>
 * </ol>
 */
public abstract class EraTask extends DefaultTask {
    /** The era's Java feature version (8, 16, 17, 21, 25...). */
    @Input
    public abstract Property<Integer> getJava();

    /** Bundle directories ({@code classes/} + {@code bundle.properties}). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getBundles();

    /** Only suites whose area (the package that holds their main) or bundle name is listed; empty is all. */
    @Input
    public abstract ListProperty<String> getSuites();

    /** Package prefix the downgrader's Java API backports are shaded under (unique per artifact). */
    @Input
    public abstract Property<String> getShadePrefix();

    /** JvmDowngrader's all-in-one jar. */
    @Classpath
    public abstract ConfigurableFileCollection getDowngrader();

    /** A JVM of this era's version. */
    @Nested
    public abstract Property<JavaLauncher> getLauncher();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException, InterruptedException {
        Path out = getReportDir().get().getAsFile().toPath();
        Harness.deleteRecursively(out);
        Files.createDirectories(out);
        int java = getJava().get();
        String hostJava = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        String eraJava = getLauncher().get().getExecutablePath().getAsFile().getPath();
        List<String> suites = getSuites().get();

        JsonArray results = new JsonArray();
        JsonArray skipped = new JsonArray();
        List<String> failed = new ArrayList<>();
        int expected = getBundles().getFiles().size();
        for (File dir : getBundles().getFiles()) {
            if (!Files.isRegularFile(dir.toPath().resolve("bundle.properties"))) {
                // a contract not verified this run (verify off), or no pure layer declared: named, not dropped
                skipped.add(dir.getName());
                continue;
            }
            Harness.Bundle bundle = Harness.Bundle.read(dir.toPath());
            String slug = bundle.name().replaceAll("[^A-Za-z0-9._-]", "_");
            Path work = out.resolve(slug);
            Files.createDirectories(work);
            JsonObject r = new JsonObject();
            r.addProperty("bundle", bundle.name());
            r.addProperty("compiledFor", bundle.release());
            results.add(r);

            Path modern = work.resolve("modern.jar");
            Harness.jar(bundle.classes(), modern);
            Path eraJar = modern;
            if (java < bundle.release()) {
                Path down = work.resolve("downgraded.jar");
                eraJar = work.resolve("java" + java + ".jar");
                Path toolLog = work.resolve("jvmdg.log");
                String tool = getDowngrader().getSingleFile().getPath();
                if (!tool(work, toolLog, hostJava, "-jar", tool, "-c", String.valueOf(44 + java), "downgrade",
                        "--target", modern.toString(), down.toString())) {
                    fail(r, failed, bundle, "downgrade", "could not downgrade to Java " + java + " (" + toolLog + ")");
                    continue;
                }
                if (!tool(work, toolLog, hostJava, "-jar", tool, "shade", "--prefix", getShadePrefix().get(),
                        "--target", down.toString(), eraJar.toString())) {
                    fail(r, failed, bundle, "shade", "could not shade the backports (" + toolLog + ")");
                    continue;
                }
                List<String> stubs = Linkage.missingStubs(eraJar, getShadePrefix().get());
                JsonArray s = new JsonArray();
                stubs.forEach(s::add);
                r.add("missingStubs", s);
                if (!stubs.isEmpty()) {
                    fail(r, failed, bundle, "stubs", "no Java " + java + " backport (the first call throws):\n      "
                            + String.join("\n      ", stubs));
                    continue;
                }
                r.addProperty("bytecode", "downgraded to class version " + (44 + java));
            } else {
                r.addProperty("bytecode", "as compiled");
            }

            Map<String, String> refs = Linkage.outsideReferences(eraJar);
            Path linkLog = work.resolve("linkage.log");
            int unresolved = Linkage.probe(refs, work, eraJava, linkLog);
            r.addProperty("outsideReferences", refs.size());
            r.addProperty("unresolved", unresolved);
            if (unresolved != 0) {
                List<String> lines = Files.readAllLines(linkLog, StandardCharsets.UTF_8).stream()
                        .filter(l -> l.startsWith("UNRESOLVED")).limit(20).toList();
                fail(r, failed, bundle, "linkage", unresolved + " reference(s) missing on Java " + java + ":\n      "
                        + String.join("\n      ", lines));
                continue;
            }

            JsonArray ran = new JsonArray();
            int failures = 0;
            for (String main : bundle.mains()) {
                if (!suites.isEmpty() && !suites.contains(area(main)) && !suites.contains(bundle.name())) {
                    continue;
                }
                Path log = work.resolve(area(main) + ".log");
                int exit = exec(work, log, eraJava, "-cp", eraJar.toString(), main);
                JsonObject m = new JsonObject();
                m.addProperty("main", main);
                m.addProperty("exit", exit);
                ran.add(m);
                if (exit != 0) {
                    failures++;
                    failed.add(bundle.name() + " " + main + " exit " + exit + " on Java " + java + " (" + log + ")");
                }
            }
            r.add("suites", ran);
            r.addProperty("status", failures == 0 ? "pass" : "fail");
            getLogger().lifecycle("Mycelium: {} on Java {} ({}; {} outside references resolve; {} suite(s){})", bundle.name(), java,
                    r.get("bytecode").getAsString(), refs.size(), ran.size(), failures == 0 ? " pass" : ", " + failures + " FAILED");
        }

        // A gate over nothing must not read as a gate that held: it says what it expected, checked and skipped.
        JsonObject report = new JsonObject();
        report.addProperty("schema", 3);
        report.addProperty("java", java);
        report.addProperty("eraJava", eraJava);
        report.addProperty("expected", expected);
        report.addProperty("checked", results.size());
        report.add("skipped", skipped);
        report.add("results", results);
        if (results.isEmpty()) {
            getLogger().warn("Mycelium: the Java {} gate checked nothing ({} bundle(s) expected, none built{}); era.json records it",
                    java, expected, skipped.isEmpty() ? "" : ": " + skipped);
        }
        Files.writeString(out.resolve("era.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        if (!failed.isEmpty()) {
            throw new GradleException("Mycelium: does not hold on Java " + java + " (docs/eras.md):\n  " + String.join("\n  ", failed));
        }
    }

    /** {@code com.example.mymod.kernel.noise.Run} → {@code noise}; a contract's {@code ...v1.harness.Run} → {@code harness}. */
    static String area(String main) {
        String[] p = main.split("\\.");
        return p.length >= 2 ? p[p.length - 2] : main;
    }

    private void fail(JsonObject r, List<String> failed, Harness.Bundle bundle, String stage, String why) {
        r.addProperty("status", "fail");
        r.addProperty("stage", stage);
        failed.add(bundle.name() + " [" + stage + "] " + why);
        getLogger().lifecycle("Mycelium: {} on Java {} FAILED at {}", bundle.name(), getJava().get(), stage);
    }

    /**
     * Runs JvmDowngrader, once more if it fails: 2.0.1 has a race in its shading (a shared HashMap across threads,
     * seen 2026-09-24), so one failure is noise and two are real. A retry is written to the log.
     */
    private boolean tool(Path dir, Path log, String... command) throws IOException, InterruptedException {
        if (exec(dir, log, command) == 0) {
            return true;
        }
        Files.writeString(log, "\n[mycelium] JvmDowngrader failed; retrying once\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        getLogger().warn("Mycelium: JvmDowngrader failed once on {}; retrying", dir.getFileName());
        return exec(dir, log, command) == 0;
    }

    private static int exec(Path dir, Path log, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        return p.waitFor();
    }
}
