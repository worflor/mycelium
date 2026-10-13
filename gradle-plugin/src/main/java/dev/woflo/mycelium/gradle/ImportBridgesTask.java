package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
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
 * {@code myceliumBridgeImport}: lays out, for the target Minecraft version, the sources of each bridge the mod takes
 * (docs/decisions/0016): the variant the era table's realization row names, with the overlays that cover the version,
 * moved under the mod's namespace as a contract's model is, and the conformance cases beside them when the mod asks for them.
 * A bridge is taken when the mod's sources reference it or {@code bridges { use(...) }} names it; importing a contract does not bring
 * its bridges. A bridge the mod overrides contributes no source (the mod's class stands in) but still gets the conformance cases
 * that need none of it, so the class is held to the contract's laws on the real game; a bridge the game cannot do on this
 * version (or a build that names no version) contributes nothing and the report says why. The report, {@code bridges.json}, is
 * what the check and the evidence manifest read.
 */
public abstract class ImportBridgesTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /**
     * What the choice reads: the bridges, the era table and the registry, as files Gradle tracks when the build runs. A fingerprint
     * computed while the build is configured reads the checkout at configuration time: the configuration cache stores its value, and
     * only Gradle's tracking of that read (which throws the whole entry away on any edit) keeps the task from running on stale sources.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getChoiceSources();

    /** The bridges the mod takes, before overrides and skips: the ones it names, else the ones its sources reference ({@code myceliumDeriveContracts}). */
    @Input
    public abstract ListProperty<String> getBridges();

    @Input
    public abstract Property<String> getNamespace();

    @Input
    @Optional
    public abstract Property<String> getMinecraft();

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

    /** The data files the conformance cases need (a clock on 26.1 and later is a datapack file), for the mod's resources. */
    @OutputDirectory
    public abstract DirectoryProperty getConformanceResourcesDir();

    @OutputFile
    public abstract RegularFileProperty getReportFile();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        Path out = clean(getOutputDir().get().getAsFile().toPath());
        Path conformance = clean(getConformanceDir().get().getAsFile().toPath());
        Path resources = clean(getConformanceResourcesDir().get().getAsFile().toPath());
        JsonObject report = importInto(root, getBridges().get(), getNamespace().get(), getMinecraft().getOrNull(),
                getSkip().get(), getOverrides().get(), getConformance().get(), out, conformance, resources, getLogger()::lifecycle);
        Path file = getReportFile().get().getAsFile().toPath();
        Files.createDirectories(file.getParent());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(report) + "\n", StandardCharsets.UTF_8);
    }

    /** What the task does apart from Gradle: the self-tests run it on a scratch copy of the repository. */
    static JsonObject importInto(Path root, List<String> bridges, String namespace, String minecraft, List<String> skip,
                                 Map<String, String> overrides, boolean conformance, Path out, Path conformanceOut,
                                 java.util.function.BiConsumer<String, Object[]> log) throws IOException {
        return importInto(root, bridges, namespace, minecraft, skip, overrides, conformance, out, conformanceOut, null, log);
    }

    /**
     * @param bridges the bridges the mod takes (named, or referenced by its sources); an override takes its bridge too
     */
    static JsonObject importInto(Path root, List<String> bridges, String namespace, String minecraft, List<String> skip,
                                 Map<String, String> overrides, boolean conformance, Path out, Path conformanceOut, Path resourcesOut,
                                 java.util.function.BiConsumer<String, Object[]> log) throws IOException {
        List<String> known = Bridges.load(root).stream().map(Bridges.Bridge::slug).toList();
        for (String slug : skip) {
            if (!known.contains(slug)) {
                throw new GradleException("Mycelium bridges: skip(\"" + slug + "\") names no bridge. Bridges: " + known);
            }
        }
        List<String> wanted = new ArrayList<>(bridges);
        for (String slug : overrides.keySet()) {
            if (!wanted.contains(slug)) {
                wanted.add(slug);
            }
        }
        wanted.removeAll(skip);
        for (String slug : overrides.keySet()) {
            if (!wanted.contains(slug)) {
                throw new GradleException("Mycelium bridges: override(\"" + slug + "\", ...) names a bridge this project skips. An override replaces a"
                        + " bridge the mod would otherwise import; to write the half natively without one, skip(\"" + slug + "\") alone.");
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        if (minecraft != null) {
            report.addProperty("minecraft", minecraft);
        }
        JsonArray array = new JsonArray();
        Eras eras = minecraft != null && !wanted.isEmpty() ? Eras.load(root.toFile()) : null;
        for (String slug : wanted) {
            Bridges.Bridge bridge;
            try {
                bridge = Bridges.require(root, slug);
            } catch (IllegalStateException e) {
                throw new GradleException(e.getMessage() + ". Bridges: " + known, e);
            }
            JsonObject one = new JsonObject();
            one.addProperty("slug", slug);
            one.addProperty("id", bridge.id());
            one.addProperty("contract", bridge.contract());
            one.addProperty("port", bridge.port());
            // what the target version draws, or why it draws nothing: a build with no version, or a table with no row for it, is as
            // much "the bridge has no native half here" as a row that says none (B2 reports all three)
            Bridges.Selection selection = null;
            String why = null;
            if (minecraft == null) {
                why = "mycelium { minecraft = ... } is not set, and a bridge is chosen by the target version";
            } else if (eras.realization(bridge.divide(), minecraft) == null) {
                why = "the era table (eras/minecraft.json, \"realizations\") has no " + bridge.divide() + " row covering Minecraft " + minecraft;
            } else {
                try {
                    selection = Bridges.select(bridge, eras, minecraft);
                } catch (IllegalStateException e) {
                    throw new GradleException(e.getMessage(), e);
                }
                if (!selection.maps()) {
                    why = selection.why();
                }
            }
            if (overrides.containsKey(slug)) {
                one.addProperty("status", "override");
                one.addProperty("class", overrides.get(slug));
                one.addProperty("implements", bridge.contract() + " " + bridge.port());
                boolean cases = false;
                if (conformance) {
                    if (why == null) {
                        List<String> written = Bridges.materializeForOverride(selection, namespace, conformanceOut, resourcesOut);
                        one.addProperty("variant", selection.variant().name());
                        JsonArray files = new JsonArray();
                        written.forEach(files::add);
                        one.add("files", files);
                        cases = true;
                    } else {
                        one.addProperty("conformanceWhy", why + ", so there is no rig of the game to run the cases on");
                    }
                }
                one.addProperty("conformance", cases);
                array.add(one);
                log.accept("Mycelium: bridge {} is overridden by {} (it must implement {}){}", new Object[] {slug, overrides.get(slug), bridge.port(),
                        cases ? "; the conformance cases that need none of the bridge's source are imported to hold it to the contract" : ""});
                continue;
            }
            if (why != null) {
                one.addProperty("status", "none");
                one.addProperty("why", why);
                array.add(one);
                log.accept("Mycelium: bridge {} cannot map on Minecraft {}: {}", new Object[] {slug, minecraft == null ? "(not set)" : minecraft, why});
                continue;
            }
            List<String> written = Bridges.materialize(selection, namespace, out, conformance ? conformanceOut : null, resourcesOut);
            one.addProperty("status", "variant");
            one.addProperty("variant", selection.variant().name());
            JsonArray overlays = new JsonArray();
            selection.overlays().forEach(o -> overlays.add(o.name()));
            one.add("overlays", overlays);
            one.addProperty("conformance", conformance);
            JsonArray files = new JsonArray();
            written.forEach(files::add);
            one.add("files", files);
            array.add(one);
            log.accept("Mycelium: bridge {}: {} on Minecraft {} ({} files{})", new Object[] {slug, selection.describe(), minecraft,
                    written.size(), conformance ? ", with conformance" : ""});
        }
        for (String slug : skip) {
            JsonObject one = new JsonObject();
            one.addProperty("slug", slug);
            one.addProperty("status", "skipped");
            array.add(one);
        }
        report.add("bridges", array);
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
