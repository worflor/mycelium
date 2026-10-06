package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * {@code myceliumRange}: holds an artifact's claim of the Minecraft versions it runs on to the era table, its proofs and
 * the metadata the built artifact declares to its loader (docs/decisions/0009, {@link RangeGate}). The report is
 * {@code build/mycelium/range/range.json}, which the evidence manifest carries; a claim the gate refuses fails the build.
 */
public abstract class RangeTask extends DefaultTask {
    /** The Mycelium checkout (the era, loader and version tables, which {@link #getSourceFingerprint} covers). */
    @Internal
    public abstract DirectoryProperty getRoot();

    /** The claim, as {@link ArtifactLedger.Artifact#toJson()} writes it. */
    @Input
    public abstract Property<String> getDeclaration();

    /** The tables' fingerprint, so a change to them re-runs the gate. */
    @Input
    public abstract Property<String> getSourceFingerprint();

    /** The artifact's processed resources: where it declares what Minecraft it accepts. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getResources();

    @Internal
    public abstract DirectoryProperty getResourcesDir();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        File root = getRoot().get().getAsFile();
        ArtifactLedger.Artifact artifact = ArtifactLedger.Artifact.fromJson(
                JsonParser.parseString(getDeclaration().get()).getAsJsonObject(), "the declared range");
        Eras eras = Eras.load(root);
        Loaders loaders = Loaders.load(root);
        List<String> real = Versions.load(root);

        CompatMetadata.Found found = null;
        Loaders.Loader loader = loaders.loaders().get(artifact.loader());
        File resources = getResourcesDir().isPresent() ? getResourcesDir().get().getAsFile() : null;
        if (loader != null && loader.compat() != null && resources != null && resources.isDirectory()) {
            try {
                found = CompatMetadata.read(resources, loader.compat());
            } catch (RuntimeException e) {
                throw new GradleException("Mycelium range: " + artifact.id() + ": " + e.getMessage(), e);
            }
        }
        RangeGate.Result result = RangeGate.check(artifact, eras, loaders, real, found);

        File report = getReportFile().get().getAsFile();
        Files.createDirectories(report.toPath().getParent());
        Files.writeString(report.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result.report()) + "\n",
                StandardCharsets.UTF_8);
        if (!result.findings().isEmpty()) {
            StringBuilder message = new StringBuilder("Mycelium range: " + artifact.id() + " claims " + artifact.from() + " to "
                    + artifact.to() + " on " + artifact.loader() + ", and " + result.findings().size() + (result.findings().size() == 1
                    ? " thing does not hold:" : " things do not hold:"));
            result.findings().forEach(f -> message.append("\n  - ").append(f));
            throw new GradleException(message.toString());
        }
        JsonObject counts = result.report().getAsJsonObject("counts");
        getLogger().lifecycle("Mycelium: " + artifact.id() + " claims " + artifact.from() + " to " + artifact.to() + ": "
                + counts.get("proven").getAsInt() + " proven, " + counts.get("pending").getAsInt() + " pending, "
                + counts.get("interpolated").getAsInt() + " interpolated (claims, not runs)");
    }
}
