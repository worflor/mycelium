package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code myceliumRepository}: the Mycelium repository checked against itself ({@link RepositoryCheck}), handed what
 * only running can tell: each rejection matrix as its harness ran it ({@code myceliumVerify}'s results), and the
 * harness's own checks. Every class in {@code harness/test} is one, with a main that exits 0 when it holds:
 * {@code SeededIdentity} (Seeded draws exactly what {@code java.util.SplittableRandom} draws, so every seed recorded
 * before Seeded owned its algorithm still reproduces) and {@code ContractRunRules} (the rules every matrix keeps).
 * Registered only by {@code dev.woflo.mycelium.repository}, which only the Mycelium repository applies.
 */
@UntrackedTask(because = "it reads the whole repository, and is cheap enough to run every time")
public abstract class RepositoryCheckTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Internal
    public abstract DirectoryProperty getReportDir();

    /** {@code myceliumVerify}'s {@code results.json}. */
    @Internal
    public abstract RegularFileProperty getHarnessResults();

    @TaskAction
    public void run() throws IOException, InterruptedException {
        Path root = getRoot().get().getAsFile().toPath();
        RepositoryCheck.Report report = RepositoryCheck.run(root, matrices(getHarnessResults().get().getAsFile().toPath()));
        List<String> ran = new ArrayList<>();
        for (String problem : harnessChecks(root, ran)) {
            report.find("harness", problem);
        }
        report.result("harness", ran.size(), "harness checks held on Java " + Runtime.version().feature() + ": "
                + String.join(", ", ran));

        JsonObject json = new JsonObject();
        json.addProperty("schema", 1);
        JsonArray results = new JsonArray();
        for (RepositoryCheck.Result r : report.results()) {
            JsonObject o = new JsonObject();
            o.addProperty("check", r.check());
            o.addProperty("checked", r.checked());
            o.addProperty("summary", r.summary());
            results.add(o);
            getLogger().lifecycle("Mycelium repository: {}: {}", r.check(), r.summary());
        }
        json.add("results", results);
        JsonArray findings = new JsonArray();
        report.findings().forEach(f -> findings.add(f.toString()));
        json.add("findings", findings);
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("repository.json"), new GsonBuilder().setPrettyPrinting().create().toJson(json),
                StandardCharsets.UTF_8);
        if (!report.findings().isEmpty()) {
            List<String> lines = new ArrayList<>();
            report.findings().forEach(f -> lines.add(f.toString()));
            throw new GradleException("Mycelium: the repository does not match what it states ("
                    + lines.size() + " finding(s)):\n  " + String.join("\n  ", lines));
        }
    }

    /** Each contract's matrix size, by id, from the harness reports {@code myceliumVerify} collected. */
    static Map<String, Integer> matrices(Path results) throws IOException {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(results)) {
            return out;
        }
        JsonObject json = JsonParser.parseString(Files.readString(results, StandardCharsets.UTF_8)).getAsJsonObject();
        for (JsonElement e : json.getAsJsonArray("results")) {
            JsonObject r = e.getAsJsonObject();
            if (r.has("harness")) {
                out.put(r.get("id").getAsString(), r.getAsJsonObject("harness").getAsJsonArray("matrix").size());
            }
        }
        return out;
    }

    /** Compiles the shared harness with {@code harness/test} and runs each class there; returns what did not hold. */
    private List<String> harnessChecks(Path root, List<String> ran) throws IOException, InterruptedException {
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) {
            return List.of("no system Java compiler to build the harness checks with; run Gradle on a JDK");
        }
        Path out = getReportDir().get().getAsFile().toPath();
        Path classes = out.resolve("harness-checks");
        Harness.deleteRecursively(classes);
        Files.createDirectories(classes);
        Path tests = root.resolve("harness/test");
        List<Path> checks = Harness.javaFiles(List.of(tests));
        if (checks.isEmpty()) {
            return List.of("harness/test holds no checks: the harness's own laws (Seeded's identity, the matrix rules) go unproven");
        }
        List<Path> sources = new ArrayList<>(Harness.sources(root));
        for (Path p : checks) {
            if (!sources.contains(p)) {
                sources.add(p);
            }
        }
        List<String> args = new ArrayList<>(List.of("--release", String.valueOf(VerifyContractsTask.RELEASE), "-d", classes.toString()));
        sources.forEach(p -> args.add(p.toString()));
        Path log = out.resolve("harness-checks.log");
        try (var err = Files.newOutputStream(log)) {
            if (javac.run(null, err, err, args.toArray(String[]::new)) != 0) {
                return List.of("the harness checks did not compile (see " + log + ")");
            }
        }
        String java = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        List<String> problems = new ArrayList<>();
        for (Path p : checks) {
            String main = tests.relativize(p).toString().replace(File.separatorChar, '.').replaceAll("\\.java$", "");
            Process process = new ProcessBuilder(java, "-cp", classes.toString(), main)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            int exit = process.waitFor();
            String name = main.substring(main.lastIndexOf('.') + 1);
            if (exit == 0) {
                ran.add(name);
            } else {
                problems.add(name + " did not hold (exit " + exit + ", see " + log + ")");
            }
        }
        return problems;
    }
}
