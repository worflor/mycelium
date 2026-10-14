package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
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

    /**
     * The era table, the registry and the compiler's models, so a change to any re-runs the task: files Gradle tracks when the build
     * runs, never a value computed while it is configured.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getCompilerSources();

    /** The mod's check levels and allowances ({@link Checks#decode}): PR1, a problem compiling the programs. */
    @Input
    public abstract ListProperty<String> getChecks();

    @Internal
    public abstract DirectoryProperty getSources();

    /** Where the catalog is (it may be outside {@link #getSources()}); tracked through {@link #getCatalogFiles()}. */
    @Internal
    public abstract RegularFileProperty getComponents();

    /** Where the lock is; tracked through {@link #getCatalogFiles()}. */
    @Internal
    public abstract RegularFileProperty getLock();

    /**
     * The catalog and the lock as inputs: files that may be absent (a missing catalog is the build's own diagnostic, not a Gradle
     * validation failure), and that may sit outside the sources directory.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getCatalogFiles();

    /** Every file of the consumer's presentation sources. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    /**
     * Where the compiled model is kept between runs. Not inside {@link #getReportDir()} or {@link #getOutputDir()}, which this task owns
     * and rewrites, and not shared with {@code myceliumPresentationLock}, which keeps its own.
     */
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
        Presentation.Outcome mosaics = Presentation.compileMosaics(root, getWorkDir().get().getAsFile().toPath(), minecraft,
                getSources().get().getAsFile().toPath(), getComponents().get().getAsFile().toPath(), getLock().get().getAsFile().toPath());
        Files.writeString(reportDir.resolve("mosaics.json"), new GsonBuilder().setPrettyPrinting().create()
                .toJson(Presentation.summary(mosaics, minecraft, Presentation.BLOCK_CONTRACT)), StandardCharsets.UTF_8);
        if (!outcome.ok() || !mosaics.ok()) {
            // a problem leaves the item (or the block) with no model on this version: PR1 fails by default; a warn writes no files and says so
            java.util.Set<String> diagnostics = new java.util.LinkedHashSet<>(outcome.diagnostics());
            diagnostics.addAll(mosaics.diagnostics());       // a missing catalog is one finding, not one per contract
            Checks.Findings found = new Checks.Findings(Checks.decode(getChecks().get()));
            for (String d : diagnostics) {
                found.add("PR1", minecraft, d);
            }
            found.conclude(getLogger(), "presentation: " + diagnostics.size() + " problem(s) compiling for Minecraft "
                    + minecraft + " (" + outcome.realization() + ", blocks " + mosaics.realization() + ")");
            // whatever level PR1 is at, the build says what it did not do: at warn or off the finding is quiet or absent, and an item
            // that is drawn with no model should never be a surprise at the end of a green build
            int programs = Presentation.programs(getSources().get().getAsFile().toPath(), getComponents().get().getAsFile().toPath(),
                    getLock().get().getAsFile().toPath());
            int blocks = Presentation.mosaics(getSources().get().getAsFile().toPath(), getComponents().get().getAsFile().toPath(),
                    getLock().get().getAsFile().toPath());
            getLogger().lifecycle("Mycelium: {} item(s) and {} block mosaic(s) have no selection on Minecraft {}; no presentation files written",
                    programs, blocks, minecraft);
            return;
        }
        for (Presentation.Outcome done : java.util.List.of(outcome, mosaics)) {
            for (Map.Entry<String, String> file : done.files().entrySet()) {
                Path to = out.resolve(file.getKey());
                Files.createDirectories(to.getParent());
                Files.writeString(to, file.getValue(), StandardCharsets.UTF_8);
            }
        }
        getLogger().lifecycle("Mycelium: compiled {} item presentation program(s) as {} and {} block mosaic(s) as {} for Minecraft {}; {} file(s) written",
                outcome.items(), outcome.realization(), mosaics.items(), mosaics.realization(), minecraft,
                outcome.files().size() + mosaics.files().size());
    }
}
