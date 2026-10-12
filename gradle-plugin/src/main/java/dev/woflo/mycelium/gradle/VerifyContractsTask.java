package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles the shared harness plus each selected contract package and runs its {@code harness.Run}.
 * Every harness must exit 0 (reference clean, every rejection-matrix entry caught, a {@code NoOp*} entry among them).
 * This is a build gate; it is also only one input to acceptance (DESIGN §11): a green exit code proves the design-time
 * harness, never game-target behavior.
 *
 * <p>{@code results.json} carries each harness's own report ({@code mycelium.harness.ContractRun} writes it): what the
 * reference ran and every matrix entry, as run, so nothing downstream parses a package's source to learn its matrix.
 */
public abstract class VerifyContractsTask extends DefaultTask {
    /** The release contracts and the harness are written in; older eras get downgraded bytecode (docs/eras.md). */
    static final int RELEASE = 17;

    /** The system property {@code mycelium.harness.ContractRun} writes its report to. */
    static final String REPORT_PROPERTY = "mycelium.harness.report";

    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract Property<String> getSourceFingerprint();

    /** Empty means every contract in the registry. */
    @Input
    public abstract ListProperty<String> getContracts();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException, InterruptedException {
        Path root = getRoot().get().getAsFile().toPath();
        Path reportDir = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(reportDir);
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        List<ContractRegistry.Entry> entries = registry.resolve(getContracts().get());

        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            throw new GradleException("Mycelium: no system Java compiler; run Gradle on a JDK");
        }
        String javaHome = System.getProperty("java.home");
        File javaBin = new File(javaHome, "bin/java" + (File.separatorChar == '\\' ? ".exe" : ""));

        JsonArray results = new JsonArray();
        List<String> failed = new ArrayList<>();
        for (ContractRegistry.Entry entry : entries) {
            Path pkgSrc = root.resolve("contracts").resolve(entry.path()).resolve("src");
            if (!Files.isDirectory(pkgSrc)) {
                JsonObject r = new JsonObject();
                r.addProperty("id", entry.id());
                r.addProperty("status", "missing");
                results.add(r);
                failed.add(entry.id() + " (package missing at " + pkgSrc + ")");
                continue;
            }
            Path bundle = reportDir.resolve(entry.slug());
            Harness.deleteRecursively(bundle);
            Path classes = bundle.resolve("classes");
            Files.createDirectories(classes);
            List<String> args = new ArrayList<>(List.of("--release", String.valueOf(RELEASE), "-d", classes.toString()));
            for (Path p : sources(root, pkgSrc, registry, entry)) {
                args.add(p.toString());
            }
            Path compileLog = reportDir.resolve(entry.slug() + ".javac.log");
            int compileExit;
            try (var err = Files.newOutputStream(compileLog)) {
                compileExit = javac.run(null, err, err, args.toArray(String[]::new));
            }
            JsonObject r = new JsonObject();
            r.addProperty("id", entry.id());
            r.addProperty("main", entry.runMain());
            r.addProperty("compileExit", compileExit);
            if (compileExit != 0) {
                r.addProperty("status", "compile-failed");
                results.add(r);
                failed.add(entry.id() + " (compile failed, see " + compileLog + ")");
                continue;
            }
            Harness.Bundle.write(bundle, entry.id(), RELEASE, List.of(entry.runMain()));
            Path runLog = reportDir.resolve(entry.slug() + ".log");
            Path harnessReport = reportDir.resolve(entry.slug() + ".harness.json");
            Files.deleteIfExists(harnessReport);
            Process process = new ProcessBuilder(javaBin.getPath(), "-D" + REPORT_PROPERTY + "=" + harnessReport,
                    "-cp", classes.toString(), entry.runMain())
                    .redirectErrorStream(true)
                    .redirectOutput(runLog.toFile())
                    .start();
            int exit = process.waitFor();
            r.addProperty("exit", exit);
            r.addProperty("status", exit == 0 ? "pass" : "fail");
            r.addProperty("log", runLog.getFileName().toString());
            if (Files.isRegularFile(harnessReport)) {
                r.add("harness", JsonParser.parseString(Files.readString(harnessReport, StandardCharsets.UTF_8)));
            } else if (exit == 0) {
                // exit 0 without a report: the package's Run does not run on ContractRun (the repository check says so)
                r.addProperty("status", "unreported");
                failed.add(entry.id() + " (exited 0 but wrote no harness report: its Run must call mycelium.harness.ContractRun.run)");
            }
            results.add(r);
            getLogger().lifecycle("Mycelium: {} harness exit={} ({})", entry.id(), exit, runLog.getFileName());
            if (exit != 0) {
                failed.add(entry.id() + " (harness exit " + exit + ", see " + runLog + ")");
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 2);
        report.addProperty("javaHome", javaHome);
        report.add("results", results);
        Files.writeString(reportDir.resolve("results.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);

        if (!failed.isEmpty()) {
            throw new GradleException("Mycelium contract harness failed:\n  " + String.join("\n  ", failed));
        }
    }

    /** The shared harness (harness/sources.txt) and the package, each file once. */
    private static List<Path> sources(Path root, Path pkgSrc, ContractRegistry registry, ContractRegistry.Entry entry)
            throws IOException {
        List<Path> out = new ArrayList<>(Harness.sources(root));
        // the models of the contracts this one requires, and nothing else of theirs
        for (ContractRegistry.Entry required : registry.requiresClosure(entry)) {
            Path model = root.resolve("contracts").resolve(required.path()).resolve("src")
                    .resolve(required.pkg().replace('.', '/')).resolve("model");
            for (Path p : Harness.javaFiles(List.of(model))) {
                if (!out.contains(p)) {
                    out.add(p);
                }
            }
        }
        for (Path p : Harness.javaFiles(List.of(pkgSrc))) {
            if (!out.contains(p)) {
                out.add(p);
            }
        }
        return out;
    }
}
