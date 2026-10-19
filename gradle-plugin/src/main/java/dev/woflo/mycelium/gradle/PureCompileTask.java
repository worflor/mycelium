package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaCompiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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

    /** Compiler selected to support the pure layer's release, independent of the JVM running Gradle. */
    @Nested
    public abstract Property<JavaCompiler> getJavaCompiler();

    @Input
    public abstract Property<String> getSuiteMain();

    /** Every lint, and a warning is an error ({@link PureSpec#getStrict()}). */
    @Input
    public abstract Property<Boolean> getStrict();

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

        List<String> args = new ArrayList<>(List.of("--release", String.valueOf(getRelease().get()), "-encoding", "UTF-8",
                "-d", classes.toString()));
        // strict: every lint, and a warning fails the build; otherwise the compile is only the purity proof
        args.addAll(getStrict().get() ? List.of("-Xlint:all", "-Werror") : List.of("-nowarn"));
        files.forEach(p -> args.add(p.toString()));
        Path log = out.resolve("javac.log");
        int exit;
        Path argumentFile = out.resolve("javac.args");
        String argumentFileContents = args.stream()
                .map(PureCompileTask::quoteArgumentFileArgument)
                .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
        Files.writeString(argumentFile, argumentFileContents, StandardCharsets.UTF_8);
        Process javac = null;
        try {
            javac = new ProcessBuilder(
                    getJavaCompiler().get().getExecutablePath().getAsFile().getAbsolutePath(),
                    "-J-Dfile.encoding=UTF-8",
                    "@" + argumentFile.toAbsolutePath())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            exit = javac.waitFor();
        } catch (InterruptedException interrupted) {
            if (javac != null) {
                javac.destroyForcibly();
            }
            Thread.currentThread().interrupt();
            throw new GradleException("Mycelium: interrupted while compiling the pure layer with its Java toolchain", interrupted);
        } finally {
            Files.deleteIfExists(argumentFile);
        }
        if (exit != 0) {
            throw new GradleException("Mycelium: the pure layer does not compile on its own (no Minecraft on the path"
                    + (getStrict().get() ? "; strict: a warning is an error" : "") + "):\n"
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

    /** Quotes one javac command-file argument, including platform path separators and embedded whitespace. */
    static String quoteArgumentFileArgument(String argument) {
        StringBuilder quoted = new StringBuilder(argument.length() + 2).append('"');
        for (int i = 0; i < argument.length(); i++) {
            char ch = argument.charAt(i);
            switch (ch) {
                case '\\' -> quoted.append("\\\\");
                case '"' -> quoted.append("\\\"");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                case '\f' -> quoted.append("\\f");
                default -> quoted.append(ch);
            }
        }
        return quoted.append('"').toString();
    }
}
