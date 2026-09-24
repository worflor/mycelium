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
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Eras (docs/eras.md): the pure layer is written in modern Java and must run on the Java of every Minecraft era a
 * mod targets. One task per era: each selected contract harness, as {@code myceliumVerify} compiled it, runs on a real
 * JVM of that version (a Gradle toolchain, provisioned if absent). Where the era is older than the compiled release,
 * the classes are first downgraded to its class version by JvmDowngrader, with its Java API backports shaded in. The
 * reference must stay clean and every rejection must still be caught, in every era.
 *
 * <p>Java 8 is the oldest any loader needs, from Forge on 1.7.10 to Babric on Beta 1.7.3. Found with Somnium
 * (2026-09-24).
 */
public abstract class ErasTask extends DefaultTask {
    /** The release myceliumVerify compiles contracts for; eras from here up run those classes unchanged. */
    static final int COMPILED_RELEASE = 17;

    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract ListProperty<String> getContracts();

    /** The era's Java feature version (8, 16, 17, 21, 25...). */
    @Input
    public abstract Property<Integer> getFloor();

    @Input
    public abstract Property<String> getSourceFingerprint();

    /** Where {@code myceliumVerify} left each package's classes ({@code <slug>/classes}). */
    @Internal
    public abstract DirectoryProperty getVerifyReportDir();

    /** JvmDowngrader's all-in-one jar. */
    @Classpath
    public abstract ConfigurableFileCollection getDowngrader();

    /** A JVM of the floor's version. */
    @Nested
    public abstract Property<JavaLauncher> getLauncher();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException, InterruptedException {
        Path root = getRoot().get().getAsFile().toPath();
        Path verified = getVerifyReportDir().get().getAsFile().toPath();
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        int floor = getFloor().get();
        int classVersion = 44 + floor;
        File downgrader = getDowngrader().getSingleFile();
        String hostJava = javaIn(System.getProperty("java.home"));
        String eraJava = getLauncher().get().getExecutablePath().getAsFile().getPath();

        JsonArray results = new JsonArray();
        List<String> failed = new ArrayList<>();
        for (ContractRegistry.Entry entry : ContractRegistry.load(root.toFile()).resolve(getContracts().get())) {
            Path classes = verified.resolve(entry.slug()).resolve("classes");
            JsonObject r = new JsonObject();
            r.addProperty("id", entry.id());
            results.add(r);
            if (!Files.isDirectory(classes)) {
                r.addProperty("status", "not-verified");
                failed.add(entry.id() + " (no verified classes at " + classes + ")");
                continue;
            }
            Path work = out.resolve(entry.slug());
            Files.createDirectories(work);
            Path runLog = out.resolve(entry.slug() + ".log");
            Files.deleteIfExists(runLog);
            if (floor >= COMPILED_RELEASE) {
                // As compiled: this era runs the classes myceliumVerify built, unchanged.
                int exit = exec(work, runLog, eraJava, "-cp", classes.toString(), entry.runMain());
                record(r, exit, entry, floor, runLog, failed, "as compiled");
                continue;
            }
            Path in = work.resolve("modern.jar");
            Path down = work.resolve("downgraded.jar");
            Path era = work.resolve("java" + floor + ".jar");
            jar(classes, in);
            Path toolLog = work.resolve("jvmdg.log");
            Files.deleteIfExists(toolLog);
            if (exec(work, toolLog, hostJava, "-jar", downgrader.getPath(), "-c", String.valueOf(classVersion),
                    "downgrade", "--target", in.toString(), down.toString()) != 0
                    || exec(work, toolLog, hostJava, "-jar", downgrader.getPath(),
                    "shade", "--prefix", "mycelium_jvmdg", "--target", down.toString(), era.toString()) != 0) {
                r.addProperty("status", "downgrade-failed");
                failed.add(entry.id() + " (could not downgrade to Java " + floor + ", see " + toolLog + ")");
                continue;
            }
            int exit = exec(work, runLog, eraJava, "-cp", era.toString(), entry.runMain());
            record(r, exit, entry, floor, runLog, failed, "downgraded");
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("floor", floor);
        report.addProperty("classVersion", classVersion);
        report.addProperty("eraJava", eraJava);
        report.addProperty("downgrader", downgrader.getName());
        report.add("results", results);
        Files.writeString(out.resolve("eras.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        if (!failed.isEmpty()) {
            throw new GradleException("Mycelium: the pure layer does not hold on Java " + floor + ":\n  " + String.join("\n  ", failed));
        }
    }

    private void record(JsonObject r, int exit, ContractRegistry.Entry entry, int floor, Path runLog, List<String> failed, String how) {
        r.addProperty("exit", exit);
        r.addProperty("bytecode", how);
        r.addProperty("status", exit == 0 ? "pass" : "fail");
        getLogger().lifecycle("Mycelium: {} on Java {} ({}) exit={}", entry.id(), floor, how, exit);
        if (exit != 0) {
            failed.add(entry.id() + " (Java " + floor + " harness exit " + exit + ", see " + runLog + ")");
        }
    }

    private static String javaIn(String home) {
        return new File(home, "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
    }

    private static int exec(Path dir, Path log, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        return p.waitFor();
    }

    private static void jar(Path classes, Path target) throws IOException {
        try (OutputStream os = Files.newOutputStream(target); JarOutputStream jar = new JarOutputStream(os);
             Stream<Path> walk = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isRegularFile(p)) {
                    jar.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                    Files.copy(p, jar);
                    jar.closeEntry();
                }
            }
        }
    }
}
