package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code myceliumPresentationLock}: appends to the committed lock the legacy codes the programs need and it lacks, in the
 * consumer's sources, so a reviewer sees the added lines in a diff. Existing entries are never touched (law S3); it runs only
 * when asked, and never as part of a build. A program the compiler refuses for any other reason changes nothing.
 */
public abstract class PresentationLockTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Internal
    public abstract DirectoryProperty getSources();

    @Internal
    public abstract RegularFileProperty getComponents();

    @Internal
    public abstract RegularFileProperty getLock();

    @Internal
    public abstract DirectoryProperty getWorkDir();

    public PresentationLockTask() {
        // it edits a source, so there is never an up-to-date answer
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    public void run() throws IOException {
        Path lock = getLock().get().getAsFile().toPath();
        Presentation.Outcome outcome = Presentation.complete(getRoot().get().getAsFile().toPath(), getWorkDir().get().getAsFile().toPath(),
                getSources().get().getAsFile().toPath(), getComponents().get().getAsFile().toPath(), lock);
        if (!outcome.ok()) {
            throw new GradleException("Mycelium presentation lock: nothing was changed, because of:\n  " + String.join("\n  ", outcome.diagnostics()));
        }
        if (outcome.added().isEmpty()) {
            getLogger().lifecycle("Mycelium: {} is complete; nothing to add", lock);
            return;
        }
        Files.createDirectories(lock.getParent());
        Files.writeString(lock, outcome.lock(), StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: added {} line(s) to {}:\n  {}", outcome.added().size(), lock, String.join("\n  ", outcome.added()));
    }
}
