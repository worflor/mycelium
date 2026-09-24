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
 * The pure layer's source laws (docs/eras.md), checked on the source so they hold on every target at once:
 * <ul>
 *   <li><b>P1 pure:</b> it mentions no Minecraft, loader or integration package, and none of the artifact's own platform
 *   packages (its other source packages); no package is split between the layers.</li>
 *   <li><b>P3 deterministic:</b> it reads no clock and draws no unseeded randomness, and does not use
 *   {@code java.util.SplittableRandom} (no Java 8 form; random.seeded@1 is the pure layer's randomness).</li>
 * </ul>
 * Comments and string literals are ignored. P2 (linkage) is checked per era on the bytecode ({@link EraTask}).
 */
public abstract class PurityTask extends DefaultTask {
    /** Always forbidden: the game, every loader, Mixin. */
    static final List<String> PLATFORM = List.of("net.minecraft", "com.mojang", "net.fabricmc", "net.neoforged",
            "net.minecraftforge", "cpw.mods", "org.spongepowered", "net.legacyfabric", "net.ornithemc", "net.modificationstation");

    /** A pure layer's time and chance come from its inputs and its seed, never from the machine. */
    static final Map<String, Pattern> NONDETERMINISM = new LinkedHashMap<>();

    static {
        NONDETERMINISM.put("the wall clock", Pattern.compile("\\bSystem\\s*\\.\\s*(currentTimeMillis|nanoTime)\\b|\\b(Instant|LocalDateTime|LocalDate|LocalTime|ZonedDateTime|OffsetDateTime)\\s*\\.\\s*now\\b|\\bClock\\s*\\.\\s*system"));
        NONDETERMINISM.put("unseeded randomness", Pattern.compile("\\bMath\\s*\\.\\s*random\\b|\\bThreadLocalRandom\\b|\\bnew\\s+(java\\.util\\.)?Random\\s*\\(\\s*\\)|\\bUUID\\s*\\.\\s*randomUUID\\b|\\bSecureRandom\\b"));
        NONDETERMINISM.put("SplittableRandom (no Java 8 form; use random.seeded@1)", Pattern.compile("\\bSplittableRandom\\b"));
        NONDETERMINISM.put("the environment", Pattern.compile("\\bSystem\\s*\\.\\s*(getenv|getProperty)\\b"));
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPureSources();

    /** The artifact's other sources: whatever package they declare is platform, and off limits to the pure layer. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPlatformSources();

    /** Packages forbidden by prefix, beyond {@link #PLATFORM} (integration APIs, the consumer's own list). */
    @Input
    public abstract ListProperty<String> getForbidden();

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

        List<String> violations = new ArrayList<>();
        for (String split : platformPackages) {
            if (purePackages.contains(split)) {
                violations.add("P1 package " + split + " is split between the pure layer and the platform");
            }
        }
        List<String> forbidden = new ArrayList<>(PLATFORM);
        forbidden.addAll(getForbidden().get());
        for (Map.Entry<Path, String> e : code.entrySet()) {
            String where = e.getKey().getFileName().toString() + " (" + packageOf(Files.readString(e.getKey(), StandardCharsets.UTF_8)) + ")";
            for (String f : forbidden) {
                if (IsolationTask.mentions(e.getValue(), f)) {
                    violations.add("P1 " + where + " mentions " + f);
                }
            }
            for (String own : platformPackages) {
                // a class directly in a platform package, or all of it: `own.Type` or `own.*`
                if (!own.isEmpty() && !purePackages.contains(own)
                        && Pattern.compile("(?<![\\w.])" + Pattern.quote(own) + "\\.(?:[A-Z_$]|\\*)").matcher(e.getValue()).find()) {
                    violations.add("P1 " + where + " reaches the platform package " + own);
                }
            }
            for (Map.Entry<String, Pattern> n : NONDETERMINISM.entrySet()) {
                Matcher m = n.getValue().matcher(e.getValue());
                if (m.find()) {
                    violations.add("P3 " + where + " uses " + n.getKey() + ": " + m.group().strip());
                }
            }
        }

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("pureFiles", pure.size());
        JsonArray pk = new JsonArray();
        purePackages.forEach(pk::add);
        report.add("purePackages", pk);
        report.addProperty("platformPackages", platformPackages.size());
        JsonArray v = new JsonArray();
        violations.forEach(v::add);
        report.add("violations", v);
        Path out = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("purity.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
        if (!violations.isEmpty()) {
            throw new GradleException("Mycelium: the pure layer is not pure (docs/eras.md):\n  " + String.join("\n  ", violations));
        }
        getLogger().lifecycle("Mycelium: pure layer holds P1 and P3 ({} files in {} packages; {} platform packages kept out)",
                pure.size(), purePackages.size(), platformPackages.size());
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
