package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code myceliumBridgeImport}: lays out, for the target Minecraft version, the sources of each bridge the mod's contracts
 * bring (docs/decisions/0016): the variant the era table's realization row names, with the overlays that cover the version,
 * moved under the mod's namespace as a contract's model is, and the conformance cases beside them when the mod asks for them.
 * A bridge the mod overrides contributes nothing (the mod's class stands in); a bridge the game cannot do on this version
 * contributes nothing and the report says why. The report, {@code bridges.json}, is what the check and the evidence manifest read.
 */
public abstract class ImportBridgesTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** Fingerprint of what the choice reads: the bridges, the era table and the registry. */
    @Input
    public abstract Property<String> getSourceFingerprint();

    /** The contracts this project imports (their registry rows say which bridges come with them). */
    @Input
    public abstract ListProperty<String> getContracts();

    @Input
    public abstract Property<String> getNamespace();

    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    @Input
    public abstract ListProperty<String> getUse();

    @Input
    public abstract ListProperty<String> getSkip();

    @Input
    public abstract MapProperty<String, String> getOverrides();

    @Input
    public abstract Property<Boolean> getConformance();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @OutputDirectory
    public abstract DirectoryProperty getConformanceDir();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        Path out = clean(getOutputDir().get().getAsFile().toPath());
        Path conformance = clean(getConformanceDir().get().getAsFile().toPath());
        JsonObject report = importInto(root, getContracts().get(), getNamespace().get(), getMinecraft().getOrNull(), getUse().get(),
                getSkip().get(), getOverrides().get(), getConformance().get(), out, conformance, getLogger()::lifecycle);
        Path file = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report) + "\n", StandardCharsets.UTF_8);
    }

    /** What the task does apart from Gradle: the self-tests run it on a scratch copy of the repository. */
    static JsonObject importInto(Path root, List<String> contracts, String namespace, String minecraft, List<String> use, List<String> skip,
                                 Map<String, String> overrides, boolean conformance, Path out, Path conformanceOut,
                                 java.util.function.BiConsumer<String, Object[]> log) throws IOException {
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        List<String> wanted = new ArrayList<>(use.isEmpty() ? Bridges.broughtBy(registry, contracts) : use);
        wanted.removeAll(skip);
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        if (minecraft != null) {
            report.addProperty("minecraft", minecraft);
        }
        for (String slug : overrides.keySet()) {
            if (!wanted.contains(slug)) {
                throw new GradleException("Mycelium bridges: override(\"" + slug + "\", ...) names a bridge this project does not use ("
                        + (wanted.isEmpty() ? "its contracts bring none" : "it uses " + wanted) + "). An override replaces a bridge the mod"
                        + " would otherwise import; to write the half natively without one, skip(\"" + slug + "\").");
            }
        }
        JsonArray bridges = new JsonArray();
        Eras eras = wanted.stream().anyMatch(s -> !overrides.containsKey(s)) ? Eras.load(root.toFile()) : null;
        for (String slug : wanted) {
            Bridges.Bridge bridge;
            try {
                bridge = Bridges.require(root, slug);
            } catch (IllegalStateException e) {
                throw new GradleException(e.getMessage() + ". Bridges: " + Bridges.load(root).stream().map(Bridges.Bridge::slug).toList(), e);
            }
            JsonObject one = new JsonObject();
            one.addProperty("slug", slug);
            one.addProperty("id", bridge.id());
            one.addProperty("contract", bridge.contract());
            one.addProperty("port", bridge.port());
            if (overrides.containsKey(slug)) {
                one.addProperty("status", "override");
                one.addProperty("class", overrides.get(slug));
                one.addProperty("implements", bridge.contract() + " " + bridge.port());
                bridges.add(one);
                log.accept("Mycelium: bridge {} is overridden by {} (it must implement {})", new Object[] {slug, overrides.get(slug), bridge.port()});
                continue;
            }
            if (minecraft == null) {
                throw new GradleException("Mycelium bridges: the bridge " + slug + " is chosen by the target version, and mycelium { minecraft = ... } is not set");
            }
            Bridges.Selection selection = Bridges.select(bridge, eras, minecraft);
            if (!selection.maps()) {
                one.addProperty("status", "none");
                one.addProperty("why", selection.why());
                bridges.add(one);
                log.accept("Mycelium: bridge {} cannot map on Minecraft {}: {}", new Object[] {slug, minecraft, selection.why()});
                continue;
            }
            List<String> written = Bridges.materialize(selection, namespace, out, conformance ? conformanceOut : null);
            one.addProperty("status", "variant");
            one.addProperty("variant", selection.variant().name());
            JsonArray overlays = new JsonArray();
            selection.overlays().forEach(o -> overlays.add(o.name()));
            one.add("overlays", overlays);
            one.addProperty("conformance", conformance);
            JsonArray files = new JsonArray();
            written.forEach(files::add);
            one.add("files", files);
            bridges.add(one);
            log.accept("Mycelium: bridge {}: {} on Minecraft {} ({} files{})", new Object[] {slug, selection.describe(), minecraft,
                    written.size(), conformance ? ", with conformance" : ""});
        }
        for (String slug : skip) {
            JsonObject one = new JsonObject();
            one.addProperty("slug", slug);
            one.addProperty("status", "skipped");
            bridges.add(one);
        }
        report.add("bridges", bridges);
        return report;
    }

    private static Path clean(Path dir) throws IOException {
        if (Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.createDirectories(dir);
        return dir;
    }
}
