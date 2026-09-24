package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles the pure layer with its suites and the Mycelium harness, as written (at its release), into a bundle that
 * every era gate runs ({@link EraTask}). No Minecraft is on the path: if it compiles here, it is pure in fact.
 */
public abstract class PureCompileTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract Property<String> getHarnessFingerprint();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getTests();

    @Input
    public abstract Property<Integer> getRelease();

    @Input
    public abstract Property<String> getSuiteMain();

    @Input
    public abstract Property<String> getBundleName();

    @OutputDirectory
    public abstract DirectoryProperty getBundleDir();

    @TaskAction
    public void run() throws IOException {
        Path out = getBundleDir().get().getAsFile().toPath();
        Harness.deleteRecursively(out);
        Path classes = out.resolve("classes");
        Files.createDirectories(classes);

        List<Path> tests = Harness.javaFiles(PurityTask.paths(getTests()));
        List<Path> files = new ArrayList<>(Harness.sources(getRoot().get().getAsFile().toPath()));
        for (Path p : Harness.javaFiles(PurityTask.paths(getSources()))) {
            if (!files.contains(p)) {
                files.add(p);
            }
        }
        tests.stream().filter(p -> !files.contains(p)).forEach(files::add);

        List<String> args = new ArrayList<>(List.of("--release", String.valueOf(getRelease().get()), "-nowarn", "-d", classes.toString()));
        files.forEach(p -> args.add(p.toString()));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        Path log = out.resolve("javac.log");
        int exit;
        try (var err = Files.newOutputStream(log)) {
            exit = javac.run(null, err, err, args.toArray(String[]::new));
        }
        if (exit != 0) {
            throw new GradleException("Mycelium: the pure layer does not compile on its own (no Minecraft on the path):\n"
                    + Files.readString(log, StandardCharsets.UTF_8));
        }

        String suiteFile = getSuiteMain().get() + ".java";
        List<String> mains = new ArrayList<>();
        for (Path t : tests) {
            if (t.getFileName().toString().equals(suiteFile)) {
                String text = Files.readString(t, StandardCharsets.UTF_8);
                if (text.contains("static void main(")) {
                    String pkg = PurityTask.packageOf(text);
                    mains.add((pkg.isEmpty() ? "" : pkg + ".") + getSuiteMain().get());
                }
            }
        }
        mains.sort(null);
        Harness.Bundle.write(out, getBundleName().get(), getRelease().get(), mains);
        getLogger().lifecycle("Mycelium: pure layer compiled for Java {} ({} files, {} suites)", getRelease().get(), files.size(), mains.size());
    }
}
