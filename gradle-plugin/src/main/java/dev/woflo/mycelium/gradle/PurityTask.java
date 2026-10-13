package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure layer's source laws (docs/eras.md), checked on the source so they hold on every target at once. These are
 * explanations, not the proof: {@code myceliumPureCompile} (the layer compiles alone) and the era gates are the proof
 * (docs/decisions/0017), so nothing here is a dependency of {@code compileJava}; it runs in {@code check}.
 * <ul>
 *   <li><b>P1 pure</b> (warn): it mentions no Minecraft, loader or integration package. <b>P1.split</b> (fail): no package
 *   is split between the pure layer and the platform sources.</li>
 *   <li><b>P3 deterministic</b> (warn): it reads no clock, environment or unseeded randomness. A policy: a mod that
 *   declares a law this breaks sets it to {@code fail}. {@code SplittableRandom} is not matched: an era with no such class
 *   is caught by the era gate's stub check (P2), which is the real reason.</li>
 * </ul>
 * Comments and string literals are ignored. P2 (linkage) is checked per era on the bytecode ({@link EraTask}).
 */
public abstract class PurityTask extends DefaultTask {
    /**
     * Always reported: the game, the tools, every loader some era names ({@code eras/loaders.json}, read by
     * {@link Loaders#platformPackages()}). Declared here as data, not a list in code: a list in code drifted from the era
     * table once (Quilt was named by an era and guarded by nothing).
     */
    @Input
    public abstract ListProperty<String> getPlatformPackages();

    /** A pure layer's time and chance come from its inputs and its seed, never from the machine. */
    static final Map<String, Pattern> NONDETERMINISM = new LinkedHashMap<>();

    static {
        NONDETERMINISM.put("the wall clock", Pattern.compile("\\bSystem\\s*\\.\\s*(currentTimeMillis|nanoTime)\\b|\\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\\s*\\.\\s*now\\b|\\bClock\\s*\\.\\s*system"));
        NONDETERMINISM.put("unseeded randomness", Pattern.compile("\\bMath\\s*\\.\\s*random\\b|\\bThreadLocalRandom\\b|\\bnew\\s+(java\\.util\\.)?Random\\s*\\(\\s*\\)|\\bUUID\\s*\\.\\s*randomUUID\\b|\\bSecureRandom\\b"));
        NONDETERMINISM.put("the environment", Pattern.compile("\\bSystem\\s*\\.\\s*(getenv|getProperty)\\b"));
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPureSources();

    /** The artifact's other sources: whatever package they declare is platform, and may not be split with the pure layer. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPlatformSources();

    /** Packages reported by prefix, beyond {@link #getPlatformPackages()} (integration APIs, the consumer's own list). */
    @Input
    public abstract ListProperty<String> getForbidden();

    /** The mod's check levels and allowances ({@link Checks#decode}). */
    @Input
    public abstract ListProperty<String> getChecks();

    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    /** A string literal's contents are data, not a reference: {@code "net.minecraft"} in a message mentions nothing. */
    static final Pattern STRING = Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"");

    static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @TaskAction
    public void run() throws IOException {
        List<Path> pure = Harness.javaFiles(paths(getPureSources()));
        List<Path> platform = new ArrayList<>(Harness.javaFiles(paths(getPlatformSources())));
        platform.removeAll(pure);

        TreeSet<String> purePackages = new TreeSet<>();
        Map<Path, String> code = new LinkedHashMap<>();
        for (Path p : pure) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            code.put(p, STRING.matcher(IsolationTask.code(text)).replaceAll("\"\""));
            purePackages.add(packageOf(text));
        }
        TreeSet<String> platformPackages = new TreeSet<>();
        for (Path p : platform) {
            platformPackages.add(packageOf(Files.readString(p, StandardCharsets.UTF_8)));
        }

        Checks.Findings found = new Checks.Findings(Checks.decode(getChecks().get()));
        for (String split : platformPackages) {
            if (purePackages.contains(split)) {
                found.add("P1.split", split.replace('.', '/'), "package " + split + " is split between the pure layer and the platform");
            }
        }
        if (getPlatformPackages().get().isEmpty()) {
            throw new GradleException("Mycelium: no platform packages to keep out of the pure layer (eras/loaders.json)");
        }
        List<String> forbidden = new ArrayList<>(getPlatformPackages().get());
        forbidden.addAll(getForbidden().get());
        for (Map.Entry<Path, String> e : code.entrySet()) {
            String site = e.getKey().toString().replace('\\', '/');
            String where = e.getKey().getFileName().toString() + " (" + packageOf(Files.readString(e.getKey(), StandardCharsets.UTF_8)) + ")";
            for (String f : forbidden) {
                if (IsolationTask.mentions(e.getValue(), f)) {
                    found.add("P1", site, where + " mentions " + f + " (the pure layer is the part that needs no platform;"
                            + " myceliumPureCompile is the proof)");
                }
            }
            for (Map.Entry<String, Pattern> n : NONDETERMINISM.entrySet()) {
                Matcher m = n.getValue().matcher(e.getValue());
                if (m.find()) {
                    found.add("P3", site, where + " uses " + n.getKey() + ": " + m.group().strip()
                            + " (a pure layer's time and chance come from its inputs and seed; random.seeded@1 is its randomness)");
                }
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 2);
        report.addProperty("pureFiles", pure.size());
        JsonArray pk = new JsonArray();
        purePackages.forEach(pk::add);
        report.add("purePackages", pk);
        report.addProperty("platformPackages", platformPackages.size());
        report.add("findings", found.toJson());
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("purity.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        found.conclude(getLogger(), "the pure layer breaks a source law (docs/eras.md)");
        getLogger().lifecycle("Mycelium: pure layer checked for P1 and P3 ({} files in {} packages; {} platform packages kept out; {} finding(s))",
                pure.size(), purePackages.size(), platformPackages.size(), found.all().size());
    }

    static String packageOf(String text) {
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    static List<Path> paths(ConfigurableFileCollection files) {
        List<Path> out = new ArrayList<>();
        for (File f : files.getFiles()) {
            out.add(f.toPath());
        }
        return out;
    }
}
