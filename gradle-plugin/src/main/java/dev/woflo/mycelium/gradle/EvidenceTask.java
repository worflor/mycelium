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

    /** The Minecraft version this artifact targets; its era is recorded (eras/minecraft.json). */
    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    /** Where the era gates left {@code java<N>/era.json}. */
    @Internal
    public abstract DirectoryProperty getErasReportDir();

    /** The usage task's report dir, if it ran: whether the artifact's sources reference each contract. */
    @Internal
    public abstract DirectoryProperty getUsageReportDir();

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
        if (getMinecraft().isPresent()) {
            Eras.Era era = Eras.load(root.toFile()).of(getMinecraft().get());
            JsonObject e = new JsonObject();
            e.addProperty("minecraft", getMinecraft().get());
            e.addProperty("id", era.id());
            e.addProperty("java", era.java());
            e.addProperty("names", era.names());
            JsonArray loaders = new JsonArray();
            era.loaders().forEach(loaders::add);
            e.add("loaders", loaders);
            evidence.add("era", e);
        }
        Path erasDir = getErasReportDir().isPresent() ? getErasReportDir().get().getAsFile().toPath() : null;
        if (erasDir != null && Files.isDirectory(erasDir)) {
            JsonArray gates = new JsonArray();
            try (var dirs = Files.list(erasDir)) {
                for (Path d : (Iterable<Path>) dirs.sorted(java.util.Comparator.comparingInt(EvidenceTask::eraNumber))::iterator) {
                    Path f = d.resolve("era.json");
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    JsonObject era = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject g = new JsonObject();
                    g.addProperty("java", era.get("java").getAsInt());
                    JsonArray bundles = new JsonArray();
                    for (var r : era.getAsJsonArray("results")) {
                        JsonObject ro = r.getAsJsonObject();
                        JsonObject b = new JsonObject();
                        b.addProperty("bundle", ro.get("bundle").getAsString());
                        b.addProperty("status", ro.has("status") ? ro.get("status").getAsString() : "unknown");
                        if (ro.has("bytecode")) {
                            b.addProperty("bytecode", ro.get("bytecode").getAsString());
                        }
                        bundles.add(b);
                    }
                    g.add("bundles", bundles);
                    gates.add(g);
                }
            }
            evidence.add("eraGates", gates);
        }

        JsonObject harness = null;
        Path results = getVerifyReportDir().isPresent()
                ? getVerifyReportDir().get().getAsFile().toPath().resolve("results.json") : null;
        if (results != null && Files.isRegularFile(results)) {
            harness = JsonParser.parseString(Files.readString(results, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        JsonObject usage = null;
        Path usageFile = getUsageReportDir().isPresent()
                ? getUsageReportDir().get().getAsFile().toPath().resolve("usage.json") : null;
        if (usageFile != null && Files.isRegularFile(usageFile)) {
            usage = JsonParser.parseString(Files.readString(usageFile, StandardCharsets.UTF_8)).getAsJsonObject();
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
            if (usage != null) {
                for (var element : usage.getAsJsonArray("results")) {
                    JsonObject u = element.getAsJsonObject();
                    if (id.equals(u.get("id").getAsString())) {
                        c.addProperty("referencedBySources", u.get("referencingFiles").getAsInt() > 0);
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

    /** {@code java8} → 8, so gates list in era order. */
    private static int eraNumber(Path dir) {
        String n = dir.getFileName().toString().replaceAll("\\D", "");
        return n.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(n);
    }
}
