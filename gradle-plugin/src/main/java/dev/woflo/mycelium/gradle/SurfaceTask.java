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
import org.gradle.api.tasks.Optional;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The portability surface (docs/eras.md, "Not yet built" 2): per package of the artifact's own main sources, how many
 * references it makes to a platform package (every root {@code eras/loaders.json} names, the game's own included),
 * split into packages the consumer declares as its ports ({@code mycelium.ports}) and everything else. The second
 * number is the cost the next rung would have to eat, and the point is to make it visible and watch it fall, not to
 * gate a build by itself (that is {@code failOnGrowth}, and only when a consumer asks for it).
 *
 * <p>A reference is counted the same way {@link PurityTask} and {@link IsolationTask} already find one: a dotted
 * mention of the package immediately before a capitalized name, {@code _}, {@code $} or {@code *}, outside comments
 * and string literals. It is a textual count, not a type count: three uses of the same class on one line are three.
 */
public abstract class SurfaceTask extends DefaultTask {
    /** The artifact's own main sources (never the imported models: those are proven pure already). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSources();

    /** Every platform package root: the game, the tools, every loader an era names ({@code eras/loaders.json}). */
    @Input
    public abstract ListProperty<String> getPlatformPackages();

    /** Packages the consumer declares as its ports ({@code mycelium.ports}): expected to touch the platform. */
    @Input
    public abstract ListProperty<String> getPorts();

    /** Fail if the non-port surface grew past a checked-in baseline (schema below). Default false. */
    @Input
    public abstract Property<Boolean> getFailOnGrowth();

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBaselineFile();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @TaskAction
    public void run() throws IOException {
        if (getFailOnGrowth().get() && !getBaselineFile().isPresent()) {
            throw new GradleException("Mycelium: mycelium.surfaceFailOnGrowth is set but no baselineFile is declared;"
                    + " nothing to grow against (docs/admission.md: misconfiguration fails loudly)");
        }
        List<String> platform = getPlatformPackages().get();
        if (platform.isEmpty()) {
            throw new GradleException("Mycelium: no platform packages to measure against (eras/loaders.json)");
        }
        List<String> ports = getPorts().get();

        // package -> platform root -> count
        Map<String, Map<String, Integer>> byPackage = new TreeMap<>();
        int files = 0;
        for (File root : getSources().getFiles()) {
            if (!root.exists()) {
                continue;
            }
            try (var walk = Files.walk(root.toPath())) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p) || !p.toString().endsWith(".java")) {
                        continue;
                    }
                    files++;
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    String pkg = packageOf(text);
                    // Comments out (IsolationTask.code()), then string literal contents out too (a string does not
                    // reference a package merely by naming it in a message; PurityTask strips both the same way).
                    String code = PurityTask.STRING.matcher(IsolationTask.code(text)).replaceAll("\"\"");
                    Map<String, Integer> counts = byPackage.computeIfAbsent(pkg, k -> new TreeMap<>());
                    for (String root2 : platform) {
                        int n = count(code, root2);
                        if (n > 0) {
                            counts.merge(root2, n, Integer::sum);
                        }
                    }
                }
            }
        }

        JsonArray packagesJson = new JsonArray();
        long portReferences = 0;
        long nonPortReferences = 0;
        int packagesTouchingOutsidePorts = 0;
        Map<String, Integer> nonPortTotals = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Integer>> e : byPackage.entrySet()) {
            String pkg = e.getKey();
            boolean isPort = ports.contains(pkg);
            int total = e.getValue().values().stream().mapToInt(Integer::intValue).sum();
            JsonObject po = new JsonObject();
            po.addProperty("package", pkg);
            po.addProperty("isPort", isPort);
            po.addProperty("references", total);
            JsonObject by = new JsonObject();
            e.getValue().forEach(by::addProperty);
            po.add("byPlatformPackage", by);
            packagesJson.add(po);
            if (isPort) {
                portReferences += total;
            } else if (total > 0) {
                nonPortReferences += total;
                packagesTouchingOutsidePorts++;
                nonPortTotals.put(pkg, total);
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        JsonArray platformJson = new JsonArray();
        platform.forEach(platformJson::add);
        report.add("platformPackages", platformJson);
        JsonArray portsJson = new JsonArray();
        ports.forEach(portsJson::add);
        report.add("ports", portsJson);
        report.add("packages", packagesJson);
        JsonObject totals = new JsonObject();
        totals.addProperty("files", files);
        totals.addProperty("portReferences", portReferences);
        totals.addProperty("nonPortReferences", nonPortReferences);
        totals.addProperty("packagesTouchingPlatformOutsidePorts", packagesTouchingOutsidePorts);
        report.add("totals", totals);

        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("surface.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);

        if (getBaselineFile().isPresent() && Files.isRegularFile(getBaselineFile().get().getAsFile().toPath())) {
            JsonObject baseline = JsonParser.parseString(Files.readString(getBaselineFile().get().getAsFile().toPath(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            long baseTotal = baseline.getAsJsonObject("totals").get("nonPortReferences").getAsLong();
            List<String> grown = new ArrayList<>();
            if (nonPortReferences > baseTotal) {
                grown.add("total non-port references " + nonPortReferences + " > baseline " + baseTotal);
            }
            Map<String, Integer> baselinePer = new LinkedHashMap<>();
            for (var element : baseline.getAsJsonArray("packages")) {
                JsonObject po = element.getAsJsonObject();
                if (!po.get("isPort").getAsBoolean()) {
                    baselinePer.put(po.get("package").getAsString(), po.get("references").getAsInt());
                }
            }
            for (Map.Entry<String, Integer> e : nonPortTotals.entrySet()) {
                int base = baselinePer.getOrDefault(e.getKey(), 0);
                if (e.getValue() > base) {
                    grown.add(e.getKey() + ": " + e.getValue() + " > baseline " + base);
                }
            }
            if (!grown.isEmpty() && getFailOnGrowth().get()) {
                throw new GradleException("Mycelium: the portability surface grew past " + getBaselineFile().get().getAsFile()
                        + ":\n  " + String.join("\n  ", grown));
            }
            if (!grown.isEmpty()) {
                getLogger().warn("Mycelium: the portability surface grew past its baseline (mycelium.surfaceFailOnGrowth is"
                        + " off, so this only warns):\n  " + String.join("\n  ", grown));
            }
        }

        getLogger().lifecycle("Mycelium: portability surface — {} reference(s) to platform packages outside {} declared"
                + " port package(s), across {} package(s) ({} reference(s) inside ports; {} file(s) scanned)",
                nonPortReferences, ports.size(), packagesTouchingOutsidePorts, portReferences, files);
    }

    static String packageOf(String text) {
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /** How many times {@code root.Type}, {@code root.CONST}, {@code root._x} or {@code root.*} appears. */
    static int count(String code, String root) {
        Matcher m = Pattern.compile("(?<![\\w.])" + Pattern.quote(root) + "\\.(?:[A-Z_$]|\\*)").matcher(code);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }
}
