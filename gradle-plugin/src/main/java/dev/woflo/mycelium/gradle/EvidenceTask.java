package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Writes {@code evidence.json}: which contracts this artifact carries (with source hashes), the target
 * profile, and the design-time harness results. The manifest states what was run; it never claims
 * game-target support (DESIGN §11, scoped assurance statement).
 */
public abstract class EvidenceTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract Property<String> getSourceFingerprint();

    @Input
    public abstract ListProperty<String> getContracts();

    @Input
    public abstract Property<String> getNamespace();

    @Input
    public abstract Property<String> getTargetProfile();

    @Input
    public abstract Property<String> getArtifactId();

    @Input
    public abstract Property<String> getArtifactVersion();

    @Input
    @Optional
    public abstract Property<Boolean> getVerifyEnabled();

    /** The verify task's report dir, if verification ran. */
    @Internal
    public abstract DirectoryProperty getVerifyReportDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        List<String> ids = getContracts().get();

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", 1);
        evidence.addProperty("generatedAt", Instant.now().toString());
        JsonObject artifact = new JsonObject();
        artifact.addProperty("id", getArtifactId().get());
        artifact.addProperty("version", getArtifactVersion().get());
        artifact.addProperty("targetProfile", getTargetProfile().get());
        evidence.add("artifact", artifact);
        evidence.addProperty("namespace", getNamespace().get());

        JsonObject harness = null;
        Path results = getVerifyReportDir().isPresent()
                ? getVerifyReportDir().get().getAsFile().toPath().resolve("results.json") : null;
        if (results != null && Files.isRegularFile(results)) {
            harness = JsonParser.parseString(Files.readString(results, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        JsonArray contracts = new JsonArray();
        for (String id : ids) {
            ContractRegistry.Entry entry = registry.require(id);
            JsonObject c = new JsonObject();
            c.addProperty("id", id);
            c.addProperty("path", entry.path());
            c.addProperty("status", entry.status());
            Path modelDir = root.resolve("contracts").resolve(entry.path()).resolve("src")
                    .resolve(entry.pkg().replace('.', '/')).resolve("model");
            c.addProperty("modelSha256", Fingerprint.ofDirectory(modelDir));
            if (harness != null) {
                for (var element : harness.getAsJsonArray("results")) {
                    JsonObject r = element.getAsJsonObject();
                    if (id.equals(r.get("id").getAsString())) {
                        JsonObject h = new JsonObject();
                        h.addProperty("ran", true);
                        h.addProperty("status", r.get("status").getAsString());
                        if (r.has("exit")) {
                            h.addProperty("exit", r.get("exit").getAsInt());
                        }
                        c.add("harness", h);
                    }
                }
            }
            if (!c.has("harness")) {
                JsonObject h = new JsonObject();
                h.addProperty("ran", false);
                c.add("harness", h);
            }
            contracts.add(c);
        }
        evidence.add("contracts", contracts);

        JsonObject claims = new JsonObject();
        claims.addProperty("designTimeOnly", true);
        claims.addProperty("note", "Harness results are design-time evidence for the reference model and rejection matrix. "
                + "They imply no game-target behavior. Game-target evidence lives in gametest reports for this target profile.");
        evidence.add("claims", claims);

        Path out = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("evidence.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(evidence), StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: evidence manifest written for {} contract(s) ({})", ids.size(), getTargetProfile().get());
    }
}
