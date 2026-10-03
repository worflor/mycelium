package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies scanner-safe capsule bytecode and final runtime linkage against consumer-declared artifacts. */
public abstract class CapsuleTask extends DefaultTask {
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getArchive();

    @Input
    public abstract Property<Integer> getOuterJava();

    /** Ordered classpath roots; parent-first shadowed definitions and their hashes are reported. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getRuntimeArtifacts();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getMappings();

    @Input
    @Optional
    public abstract Property<String> getMappingFrom();

    @Input
    @Optional
    public abstract Property<String> getMappingTo();

    /** Ordered root identities make changes to classpath precedence invalidate the gate result. */
    @Input
    public List<String> getRuntimeArtifactOrder() {
        return getRuntimeArtifacts().getFiles().stream().map(file -> file.getAbsolutePath()).toList();
    }

    @Input
    public abstract ListProperty<String> getMappedPrefixes();

    /** Each value is {@code archive-entry|java-feature}. */
    @Input
    public abstract ListProperty<String> getNestedJars();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void verify() throws IOException {
        List<File> roots = new ArrayList<>(getRuntimeArtifacts().getFiles());
        if (roots.isEmpty()) {
            throw new GradleException("Mycelium: myceliumCapsule has no declared runtimeArtifacts; the gate cannot prove linkage");
        }
        List<java.nio.file.Path> rootPaths = roots.stream().map(File::toPath).toList();
        Map<String, Integer> nested = new LinkedHashMap<>();
        for (String encoded : getNestedJars().get()) {
            int split = encoded.lastIndexOf('|');
            if (split <= 0) throw new GradleException("Mycelium: malformed capsule nestedJar declaration: " + encoded);
            String entry = encoded.substring(0, split);
            int java;
            try { java = Integer.parseInt(encoded.substring(split + 1)); }
            catch (NumberFormatException e) { throw new GradleException("Mycelium: invalid nested Java feature version: " + encoded, e); }
            if (java < 1 || nested.putIfAbsent(entry, 44 + java) != null) {
                throw new GradleException("Mycelium: nestedJar entries must be unique and use a positive Java feature version: " + encoded);
            }
        }
        if (!getMappedPrefixes().get().isEmpty() && !getMappings().isPresent()) {
            throw new GradleException("Mycelium: mappedPrefixes requires a Tiny v2 mappings file");
        }
        String from = getMappingFrom().getOrElse("");
        String to = getMappingTo().getOrElse("");
        if (getMappings().isPresent() && (from.isBlank() || to.isBlank())) {
            throw new GradleException("Mycelium: mappingNamespaces(from, to) is required when capsule mappings are declared");
        }

        Linkage.CapsuleResult result;
        try {
            result = Linkage.verifyCapsule(getArchive().get().getAsFile().toPath(), 44 + getOuterJava().get(), nested,
                    rootPaths, getMappings().isPresent() ? getMappings().get().getAsFile().toPath() : null,
                    from, to, getMappedPrefixes().get());
        } catch (IllegalStateException | IOException e) {
            throw new GradleException(e.getMessage(), e);
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("archiveSha256", result.archiveHash());
        report.addProperty("mappingSha256", result.mappingsHash());
        report.addProperty("outerJavaCeiling", getOuterJava().get());
        report.addProperty("classFiles", result.classFiles());
        report.addProperty("classReferences", result.classReferences());
        report.addProperty("memberReferences", result.memberReferences());
        JsonArray rootsJson = new JsonArray();
        for (int i = 0; i < roots.size(); i++) {
            JsonObject root = new JsonObject();
            root.addProperty("order", i);
            root.addProperty("file", roots.get(i).getName());
            root.addProperty("sha256", result.runtimeRootHashes().get(i).substring(result.runtimeRootHashes().get(i).indexOf('=') + 1));
            rootsJson.add(root);
        }
        report.add("runtimeArtifacts", rootsJson);
        JsonArray shadowed = new JsonArray();
        result.shadowedRuntimeClasses().forEach(shadowed::add);
        report.add("shadowedRuntimeClasses", shadowed);
        JsonObject ceilings = new JsonObject();
        result.classMajors().forEach(ceilings::addProperty);
        report.add("classMajors", ceilings);
        JsonArray prefixes = new JsonArray();
        getMappedPrefixes().get().forEach(prefixes::add);
        report.add("mappedPrefixes", prefixes);
        File output = getReportFile().get().getAsFile();
        Files.createDirectories(output.toPath().getParent());
        Files.writeString(output.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: capsule linkage holds ({} class files, {} class references, {} member references)",
                result.classFiles(), result.classReferences(), result.memberReferences());
        if (!result.shadowedRuntimeClasses().isEmpty()) {
            getLogger().lifecycle("Mycelium: {} runtime class definitions are shadowed by earlier roots; see capsule.json",
                    result.shadowedRuntimeClasses().size());
        }
    }
}
