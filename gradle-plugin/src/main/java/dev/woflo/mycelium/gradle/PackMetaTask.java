package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code myceliumPackMeta}: writes the target's {@code pack.mcmeta} from the era table's {@code packFormat} rows
 * (docs/decisions/0017): the schema the version reads ({@link Eras.PackFormat#mcmeta}) and its formats, so a mod holds no
 * table of them. The generated directory joins the resources when {@code mycelium { packMeta { ... } }} is declared (Forge
 * serves a mod's data only with a {@code pack.mcmeta}; Fabric and NeoForge make their own).
 */
public abstract class PackMetaTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    @Input
    public abstract Property<String> getPackDescription();

    /** The era table, tracked as a file when the build runs, so a changed row re-runs the task (even from the configuration cache). */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEraTable();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        if (!getMinecraft().isPresent()) {
            throw new GradleException("Mycelium pack.mcmeta: mycelium { minecraft = \"...\" } is not set; the era table needs the target version");
        }
        String text = Eras.load(getRoot().get().getAsFile()).packFormatFor(getMinecraft().get()).mcmeta(getPackDescription().get());
        Path out = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("pack.mcmeta"), text, StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: wrote pack.mcmeta for Minecraft {}", getMinecraft().get());
    }
}
