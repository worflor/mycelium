package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * {@code myceliumPresentation}: compiles the consumer's presentation programs for the target Minecraft version into that
 * version's native artefacts and the plan its runtime adapter reads, written to a build directory that joins the
 * resources (docs/decisions/0012). The era table gives the realization; the compiler is {@code item.presentation@1}'s own.
 * Any law the programs break fails the build with the diagnostic, and a selection the committed lock lacks fails it with the
 * lines to add: the build never edits a source ({@code myceliumPresentationLock} does, on request).
 */
public abstract class PresentationTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** The target version; its era-table row chooses the realization. */
    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    /** The era table, the registry and the compiler's models, so a change to any re-runs the task. */
    @Input
    public abstract Property<String> getSourceFingerprint();

    @Internal
    public abstract DirectoryProperty getSources();

    @Internal
    public abstract RegularFileProperty getComponents();

    @Internal
    public abstract RegularFileProperty getLock();

    /** Every file of the consumer's presentation sources. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    /** Where the compiled model is kept between runs. */
    @Internal
    public abstract DirectoryProperty getWorkDir();

    /** The generated resources: the era's artefacts and the plans. */
    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        Path out = getOutputDir().get().getAsFile().toPath();
        Harness.deleteRecursively(out);
        Files.createDirectories(out);
        Path reportDir = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(reportDir);
        if (!getMinecraft().isPresent()) {
            throw new GradleException("Mycelium presentation: mycelium { minecraft = \"...\" } is not set; the era table needs the target version to choose the realization");
        }
        String minecraft = getMinecraft().get();
        Presentation.Outcome outcome = Presentation.compile(root, getWorkDir().get().getAsFile().toPath(), minecraft,
                getSources().get().getAsFile().toPath(), getComponents().get().getAsFile().toPath(), getLock().get().getAsFile().toPath());
        Files.writeString(reportDir.resolve("presentation.json"), new GsonBuilder().setPrettyPrinting().create()
                .toJson(Presentation.summary(outcome, minecraft)), StandardCharsets.UTF_8);
        if (!outcome.ok()) {
            throw new GradleException("Mycelium presentation: " + outcome.diagnostics().size() + " problem(s) compiling for Minecraft "
                    + minecraft + " (" + outcome.realization() + "):\n  " + String.join("\n  ", outcome.diagnostics()));
        }
        for (Map.Entry<String, String> file : outcome.files().entrySet()) {
            Path to = out.resolve(file.getKey());
            Files.createDirectories(to.getParent());
            Files.writeString(to, file.getValue(), StandardCharsets.UTF_8);
        }
        getLogger().lifecycle("Mycelium: compiled {} item presentation program(s) for Minecraft {} as {}; {} file(s) written",
                outcome.items(), minecraft, outcome.realization(), outcome.files().size());
    }
}
