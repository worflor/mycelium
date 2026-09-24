package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims discipline for imports (DESIGN §11): an artifact that imports a contract but never uses it would still say,
 * in its evidence manifest, that it carries it. This task looks for each imported contract's package in the
 * consumer's own sources (never the imported copies) and reports the ones nothing references. It warns by default
 * and fails with {@code mycelium { failOnUnused = true }}. Found in Somnium (2026-09-23): five of ten imported
 * contracts were never used.
 */
public abstract class UsageTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract ListProperty<String> getContracts();

    @Input
    public abstract Property<String> getNamespace();

    @Input
    public abstract Property<Boolean> getFailOnUnused();

    /** The consumer's source files. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** Where imported models were generated: never counted as use. */
    @Internal
    public abstract DirectoryProperty getImportedDir();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException {
        ContractRegistry registry = ContractRegistry.load(getRoot().get().getAsFile());
        Path imported = getImportedDir().isPresent() ? getImportedDir().get().getAsFile().toPath().toAbsolutePath() : null;
        List<String> texts = new ArrayList<>();
        for (File root : getSources().getFiles()) {
            if (!root.exists()) {
                continue;
            }
            try (var walk = Files.walk(root.toPath())) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    Path abs = p.toAbsolutePath();
                    if (Files.isRegularFile(abs) && abs.toString().endsWith(".java") && (imported == null || !abs.startsWith(imported))) {
                        texts.add(Files.readString(abs, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        if (texts.isEmpty()) {
            // nothing to judge by: saying "unused" here would be the false claim this task exists to prevent
            getLogger().warn("Mycelium: no sources found to check contract usage against; usage not recorded");
            Path out = getReportDir().get().getAsFile().toPath();
            Files.createDirectories(out);
            Files.deleteIfExists(out.resolve("usage.json"));
            return;
        }
        JsonArray results = new JsonArray();
        List<String> unused = new ArrayList<>();
        for (String id : getContracts().get()) {
            ContractRegistry.Entry entry = registry.require(id);
            String needle = getNamespace().get() + "." + entry.relativePackage();
            int files = 0;
            for (String t : texts) {
                if (t.contains(needle)) {
                    files++;
                }
            }
            JsonObject r = new JsonObject();
            r.addProperty("id", id);
            r.addProperty("package", needle);
            r.addProperty("referencingFiles", files);
            results.add(r);
            if (files == 0) {
                unused.add(id);
            }
        }
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("sourcesScanned", texts.size());
        report.add("results", results);
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("usage.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        if (!unused.isEmpty()) {
            String msg = "Mycelium: imported but never referenced by this artifact's sources: " + unused
                    + " (drop them from mycelium.contracts, or use them; the evidence manifest records this)";
            if (getFailOnUnused().get()) {
                throw new GradleException(msg);
            }
            getLogger().warn(msg);
        } else {
            getLogger().lifecycle("Mycelium: every imported contract is referenced ({} source files scanned)", texts.size());
        }
    }
}
