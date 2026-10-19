package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipFile;

/** Verification reports consumed through producer dependencies, bound to the finished artifact bytes. */
public abstract class VerificationEvidenceTask extends DefaultTask {
    public abstract static class Report {
        @Input
        public abstract Property<Boolean> getEnabled();

        @InputFiles
        @PathSensitive(PathSensitivity.RELATIVE)
        public abstract ConfigurableFileCollection getFiles();

        @Input
        public abstract Property<String> getSubject();

        @Input
        public abstract Property<String> getSubjectHashPath();

        @Input
        public abstract Property<Boolean> getShippingArtifact();
    }

    @Nested
    public abstract MapProperty<String, Report> getReports();

    @Nested
    public abstract MapProperty<String, Report> getArtifacts();

    @Input
    public abstract MapProperty<String, String> getProducerPaths();

    @Input
    public abstract MapProperty<String, String> getProducerStates();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEmbeddedEvidence();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        Files.deleteIfExists(getReportFile().get().getAsFile().toPath());
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", 1);
        evidence.addProperty("phase", "verification");
        JsonArray artifacts = new JsonArray();
        Map<String, String> subjectHashes = new TreeMap<>();
        Map<String, String> states = getProducerStates().get();
        Path embedded = getEmbeddedEvidence().get().getAsFile().toPath();
        String embeddedHash = TaskOutcomes.CURRENT.equals(states.get("embedded")) ? Fingerprint.ofFile(embedded) : null;
        int shippingSubjects = 0;
        boolean shippingBound = embeddedHash != null;
        for (var entry : new TreeMap<>(getArtifacts().getOrElse(Map.of())).entrySet()) {
            Report subject = entry.getValue();
            boolean shipping = subject.getShippingArtifact().get();
            if (shipping) shippingSubjects++;
            if (!subject.getEnabled().get() || !TaskOutcomes.CURRENT.equals(states.get("artifact/" + entry.getKey()))) {
                if (shipping) shippingBound = false;
                continue;
            }
            var files = subject.getFiles().getFiles();
            if (files.size() != 1) throw new IOException("Expected one archive for subject " + entry.getKey());
            var file = files.iterator().next();
            if (!file.isFile()) throw new IOException("Artifact is not a regular file: " + file);
            JsonObject artifact = new JsonObject();
            artifact.addProperty("subject", entry.getKey());
            artifact.addProperty("name", file.getName());
            artifact.addProperty("sha256", Fingerprint.ofFile(file.toPath()));
            artifact.addProperty("size", Files.size(file.toPath()));
            artifact.addProperty("kind", shipping ? "shipping" : "checked archive");
            if (shipping) {
                boolean matches = embeddedHash != null && embeddedHash.equals(embeddedHash(file.toPath()));
                artifact.addProperty("embeddedEvidenceMatches", matches);
                shippingBound &= matches;
            }
            artifacts.add(artifact);
            subjectHashes.put(entry.getKey(), artifact.get("sha256").getAsString());
        }
        evidence.add("artifacts", artifacts);
        evidence.addProperty("artifactBound", shippingSubjects > 0 && shippingBound);
        if (shippingSubjects == 0) evidence.addProperty("why", "no shipping artifact is configured");
        else if (!shippingBound) evidence.addProperty("why", "a shipping producer is unavailable or its embedded evidence differs");
        if (embeddedHash != null) {
            evidence.addProperty("embeddedEvidenceSha256", embeddedHash);
            evidence.add("artifactEvidence", read(embedded));
        } else evidence.add("artifactEvidence", EvidenceTask.notChecked("artifact evidence producer: " + states.get("embedded")));
        JsonObject reports = new JsonObject();
        for (var entry : new TreeMap<>(getReports().getOrElse(Map.of())).entrySet()) {
            Report report = entry.getValue();
            JsonObject recorded = new JsonObject();
            if (!report.getEnabled().get() || !TaskOutcomes.CURRENT.equals(states.get("report/" + entry.getKey()))) {
                recorded.addProperty("available", false);
                recorded.addProperty("why", !report.getEnabled().get() ? "disabled or not applicable for this artifact"
                        : "producer: " + states.get("report/" + entry.getKey()));
            } else {
                var files = report.getFiles().getFiles();
                if (files.size() != 1 || !files.iterator().next().isFile()) {
                    recorded.addProperty("available", false);
                    recorded.addProperty("why", "the producer supplied no report");
                } else {
                    Path file = files.iterator().next().toPath();
                    JsonObject result = read(file);
                    String subject = report.getSubject().get();
                    if (!subject.isEmpty()) {
                        String expected = subjectHashes.get(subject);
                        var hash = (com.google.gson.JsonElement) result;
                        for (String segment : report.getSubjectHashPath().get().split("\\.")) {
                            hash = hash != null && hash.isJsonObject() ? hash.getAsJsonObject().get(segment) : null;
                        }
                        if (expected == null || hash == null || !hash.isJsonPrimitive() || !expected.equals(hash.getAsString()))
                            throw new IOException("Report " + entry.getKey() + " does not describe subject " + subject);
                        recorded.addProperty("subject", subject);
                    }
                    recorded.addProperty("available", true);
                    recorded.addProperty("sha256", Fingerprint.ofFile(file));
                    recorded.add("result", result);
                }
            }
            reports.add(entry.getKey(), recorded);
        }
        evidence.add("reports", reports);
        JsonObject producers = new JsonObject();
        new TreeMap<>(getProducerPaths().getOrElse(Map.of())).forEach((role, path) -> {
            JsonObject producer = new JsonObject();
            producer.addProperty("task", path);
            producer.addProperty("state", states.getOrDefault(role, "unobserved"));
            producers.add(role, producer);
        });
        evidence.add("producers", producers);
        evidence.addProperty("scope", "Reports describe their producers' declared inputs. Artifact hashes identify the finished bytes; "
                + "they do not extend a report's scope or establish native game behavior.");
        write(getReportFile().get().getAsFile().toPath(), evidence);
        getLogger().lifecycle("Mycelium: verification evidence records {} archive(s); shipping evidence bound: {}",
                artifacts.size(), shippingSubjects > 0 && shippingBound);
    }

    private static String embeddedHash(Path archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry("META-INF/mycelium/evidence.json");
            if (entry == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var stream = zip.getInputStream(entry)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    static void write(Path path, JsonObject evidence) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(evidence),
                    StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
