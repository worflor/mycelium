package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code myceliumLinkage}: holds the finished artifact to the game's published names on every version of its claimed range
 * that has name data (docs/decisions/0010, {@link LinkageGate}). It reads the range gate's report for the versions and what ran
 * on them, and writes {@code build/mycelium/linkage/linkage.json}: each version as {@code proven}, {@code pending},
 * {@code linked} or {@code interpolated}. It runs on the artifact as it ships, after the evidence manifest is inside it, so the
 * manifest says the check ran later and the report names the archive it read by SHA-256.
 */
public abstract class LinkageTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** The claim, as {@link ArtifactLedger.Artifact#toJson()} writes it. */
    @Input
    public abstract Property<String> getDeclaration();

    /** The tables' fingerprint, so a change to the era table re-runs the gate. */
    @Input
    public abstract Property<String> getSourceFingerprint();

    /** {@code myceliumRange}'s report: the versions of the claim and what ran on them. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRangeReport();

    /** The finished artifact. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchive();

    @Input
    public abstract ListProperty<String> getNestedJars();

    /** The naming the artifact's classes reference the game in, when the era table's is not the one ({@link LinkageSpec#getNamespace()}). */
    @Input
    @Optional
    public abstract Property<String> getNamespace();

    /** Directories of compiled classes the archive carries and the gate does not hold ({@link LinkageSpec#getExclude()}). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getExcluded();

    /** The name data's lock. */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getNames();

    @Internal
    public abstract DirectoryProperty getCache();

    /** Whether the build may not fetch: a version whose name data is not cached is then recorded, and stays as it was. */
    @Input
    public abstract Property<Boolean> getOffline();

    /** The target Minecraft version of a capsule, whose own runtime-linkage report, if present, links it (ADR 0008). */
    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCapsuleReport();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    /** The internal names of the classes under the excluded directories. */
    private java.util.Set<String> excludedClasses() throws IOException {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (File dir : getExcluded().getFiles()) {
            if (!dir.isDirectory()) {
                continue;
            }
            Path base = dir.toPath();
            try (java.util.stream.Stream<Path> walk = Files.walk(base)) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
                    String rel = base.relativize(p).toString().replace(File.separatorChar, '/');
                    out.add(rel.substring(0, rel.length() - ".class".length()));
                }
            }
        }
        return out;
    }

    @TaskAction
    public void run() throws IOException {
        File root = getRoot().get().getAsFile();
        ArtifactLedger.Artifact artifact = ArtifactLedger.Artifact.fromJson(
                JsonParser.parseString(getDeclaration().get()).getAsJsonObject(), "the declared range");
        JsonObject range = JsonParser.parseString(Files.readString(getRangeReport().get().getAsFile().toPath(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (!range.has("versions")) {
            throw new GradleException("Mycelium linkage: " + artifact.id() + ": the range report enumerates no versions (the range gate "
                    + "refused the claim); fix that first");
        }
        Path archive = getArchive().get().getAsFile().toPath();
        String archiveHash = NameCache.sha256(archive);
        References.Found found = References.scan(archive, getNestedJars().get(), excludedClasses());
        if (found.classFiles() == 0) {
            throw new GradleException("Mycelium linkage: " + artifact.id() + ": " + archive.getFileName() + " carries no class files, so there is"
                    + " nothing to link");
        }

        Map<String, String> runtimeLinked = new LinkedHashMap<>();
        if (getCapsuleReport().isPresent() && getCapsuleReport().get().getAsFile().isFile() && getMinecraft().isPresent()) {
            JsonObject capsule = JsonParser.parseString(Files.readString(getCapsuleReport().get().getAsFile().toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!archiveHash.equals(capsule.get("archiveSha256").getAsString())) {
                throw new GradleException("Mycelium linkage: " + artifact.id() + ": capsule.json describes an archive with SHA-256 "
                        + capsule.get("archiveSha256").getAsString() + ", not " + archive.getFileName() + " (" + archiveHash + ")");
            }
            runtimeLinked.put(getMinecraft().get(), "the capsule's final runtime view, " + capsule.getAsJsonArray("runtimeArtifacts").size()
                    + " pinned runtime archives (myceliumCapsule, capsule.json)");
        }

        NameData data = NameData.load(getNames().get().getAsFile());
        NameCache cache = new NameCache(getCache().get().getAsFile().toPath(), getOffline().get());
        LinkageGate.Result result = LinkageGate.evaluate(range, artifact, getNamespace().getOrNull(), Eras.load(root), data, found,
                entry -> Names.read(entry, cache), runtimeLinked, getOffline().get());

        JsonObject report = result.report();
        JsonObject archiveJson = report.getAsJsonObject("artifact");
        archiveJson.addProperty("file", archive.getFileName().toString());
        archiveJson.addProperty("sha256", archiveHash);
        JsonArray nested = new JsonArray();
        getNestedJars().get().forEach(nested::add);
        archiveJson.add("nestedJars", nested);
        JsonObject lock = new JsonObject();
        lock.addProperty("sha256", NameCache.sha256(getNames().get().getAsFile().toPath()));
        report.add("names", lock);

        File out = getReportFile().get().getAsFile();
        Files.createDirectories(out.toPath().getParent());
        Files.writeString(out.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report) + "\n",
                StandardCharsets.UTF_8);
        if (!result.findings().isEmpty()) {
            StringBuilder message = new StringBuilder("Mycelium linkage: " + artifact.id() + " claims " + artifact.from() + " to " + artifact.to()
                    + " on " + artifact.loader() + ", and " + result.findings().size() + (result.findings().size() == 1
                    ? " version does not link:" : " versions do not link:"));
            result.findings().forEach(f -> message.append("\n  - ").append(f));
            throw new GradleException(message.toString());
        }
        JsonObject counts = report.getAsJsonObject("counts");
        getLogger().lifecycle("Mycelium: " + artifact.id() + " claims " + artifact.from() + " to " + artifact.to() + ": "
                + counts.get("proven").getAsInt() + " proven, " + counts.get("pending").getAsInt() + " pending, "
                + counts.get("linked").getAsInt() + " linked, " + counts.get("interpolated").getAsInt() + " interpolated");
    }
}
