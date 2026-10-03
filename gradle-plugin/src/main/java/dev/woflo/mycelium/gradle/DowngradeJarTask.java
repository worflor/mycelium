package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * {@code myceliumDowngradeJar}: the artifact's own classes, all of them, for an era whose Java is older than the Java they
 * were compiled for (docs/decisions/0007). {@code myceliumPureShip} does this for the pure layer alone, from the era gate's
 * own bytes; a port written in the same modern Java (records, {@code List.of}, pattern matching) needs the same for the
 * rest of the jar. JvmDowngrader downgrades the bytecode to the target's class version and backports the Java APIs it
 * calls into classes shaded under {@link #getShadePrefix()}; then the result is held to two things:
 * <ol>
 *   <li><b>Stubs:</b> no call of the artifact's own may be one the downgrader had no backport for (each would throw
 *   {@code MissingStubError} at its first call);</li>
 *   <li><b>Linkage:</b> every Java class, method and field the downgraded jar reaches ({@code java.*}, {@code javax.*})
 *   exists on a real JVM of the target's version. The game and loader references are the artifact's platform, not
 *   Java's, and stay the port's own concern.</li>
 * </ol>
 * The classes {@code myceliumPureShip} put in the jar are downgraded already and call backports of their own; this task
 * shades the rest of the jar's backports under a prefix of its own. A jar whose classes already run on the target is
 * copied through unchanged, and the report says so.
 */
public abstract class DowngradeJarTask extends DefaultTask {
    /** The jar to downgrade (a compiled, unremapped jar: the classpath below is what it was compiled against). */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getInput();

    /** What the jar was compiled against: the downgrader reads its types (hierarchies, nest hosts) to rewrite calls. */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    /** Feature library jars whose identities are made private before downgrade. Empty preserves existing artifacts. */
    @Classpath
    public abstract ConfigurableFileCollection getLibraryJars();

    @Input
    public abstract ListProperty<String> getLibraryRelocations();

    /** The Java feature version the result must run on (8, 16, 17...). */
    @Input
    public abstract Property<Integer> getJava();

    /**
     * Package prefix the backports of the jar's own classes are shaded under. Not the prefix {@code myceliumPureShip}
     * used: its classes call backports of their own under that one, and the two shades each hold only what their own
     * callers use.
     */
    @Input
    public abstract Property<String> getShadePrefix();

    /** The prefix {@code myceliumPureShip}'s backports are under, which are backports too and not the artifact's code. */
    @Input
    public abstract Property<String> getShippedShadePrefix();

    /** JvmDowngrader's all-in-one jar. */
    @Classpath
    public abstract ConfigurableFileCollection getDowngrader();

    /** A JVM of the target's version, for the linkage check. */
    @Nested
    public abstract Property<JavaLauncher> getLauncher();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    /** {@code downgrade.json}, and the tool's and the probe's logs. */
    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    /** A Windows command line holds 32,767 characters: past this the classpath is refused, not silently cut. */
    static final int MAX_CLASSPATH_CHARS = 28_000;

    @TaskAction
    public void run() throws IOException, InterruptedException {
        Path in = getInput().get().getAsFile().toPath();
        Path out = getOutput().get().getAsFile().toPath();
        Path report = getReportDir().get().getAsFile().toPath();
        Harness.deleteRecursively(report);
        Files.createDirectories(report);
        Files.createDirectories(out.getParent());
        int java = getJava().get();
        String prefix = getShadePrefix().get();

        JsonObject r = new JsonObject();
        r.addProperty("schema", 1);
        r.addProperty("targetJava", java);
        r.addProperty("classVersionBefore", maxMajor(in));
        r.addProperty("shadePrefix", prefix);

        List<String> declarations = getLibraryRelocations().get();
        if (!declarations.isEmpty() || !getLibraryJars().isEmpty()) {
            Path isolated = report.resolve("private-libraries.jar");
            List<Path> libraries = getLibraryJars().getFiles().stream().map(File::toPath).toList();
            Map<String, Integer> counts = PrivateLibraries.isolate(in, libraries, PrivateLibraries.declarations(declarations), isolated, java);
            JsonObject privateLibraries = new JsonObject();
            privateLibraries.addProperty("checked", true);
            privateLibraries.addProperty("jarSha256", PrivateLibraries.sha256(isolated));
            JsonArray packages = new JsonArray();
            for (PrivateLibraries.Relocation relocation : PrivateLibraries.declarations(declarations)) {
                JsonObject entry = new JsonObject();
                entry.addProperty("from", relocation.from());
                entry.addProperty("to", relocation.to());
                entry.addProperty("classes", counts.get(relocation.from()));
                packages.add(entry);
            }
            privateLibraries.add("packages", packages);
            JsonArray inputs = new JsonArray();
            for (Path library : libraries.stream().sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                JsonObject input = new JsonObject();
                input.addProperty("jar", library.getFileName().toString());
                input.addProperty("sha256", PrivateLibraries.sha256(library));
                inputs.add(input);
            }
            privateLibraries.add("inputs", inputs);
            r.add("privateLibraries", privateLibraries);
            in = isolated;
        } else {
            JsonObject privateLibraries = new JsonObject();
            privateLibraries.addProperty("checked", false);
            privateLibraries.addProperty("why", "no private feature libraries declared");
            r.add("privateLibraries", privateLibraries);
        }

        if (maxMajor(in) <= 44 + java) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            r.addProperty("downgraded", false);
            r.addProperty("why", "every class already runs on Java " + java + " (class version " + maxMajor(in) + ")");
            write(report, r);
            getLogger().lifecycle("Mycelium: {} already runs on Java {}; myceliumDowngradeJar copied it through", in.getFileName(), java);
            return;
        }

        String classpath = getClasspath().getFiles().stream().map(File::getPath).collect(Collectors.joining(File.pathSeparator));
        if (classpath.length() > MAX_CLASSPATH_CHARS) {
            throw new GradleException("Mycelium: the classpath given to myceliumDowngradeJar is " + classpath.length()
                    + " characters, more than a command line holds (" + MAX_CLASSPATH_CHARS + "); pass only the jars the classes"
                    + " were compiled against");
        }

        // The classes myceliumPureShip put in this jar are downgraded already (the downgrader leaves them as they are) and
        // call backports of their own under the ship's prefix. The shade below writes the backports the rest of the jar
        // calls under this task's prefix: a second prefix, not the same one, because a shade counts the members its own
        // callers use and drops the rest, so two shades under one name would each hold what the other lacks.
        Path down = report.resolve("downgraded.jar");
        Path log = report.resolve("jvmdg.log");
        String hostJava = new File(System.getProperty("java.home"), "bin/java" + (File.separatorChar == '\\' ? ".exe" : "")).getPath();
        String tool = getDowngrader().getSingleFile().getPath();
        if (!tool(report, log, hostJava, "-jar", tool, "-c", String.valueOf(44 + java), "downgrade",
                "--target", in.toString(), down.toString(), "--classpath", classpath)) {
            throw new GradleException("Mycelium: could not downgrade " + in.getFileName() + " to Java " + java + " (" + log + ")");
        }
        if (!tool(report, log, hostJava, "-jar", tool, "shade", "--prefix", prefix, "--target", down.toString(), out.toString())) {
            throw new GradleException("Mycelium: could not shade the backports of " + in.getFileName() + " (" + log + ")");
        }
        Path metadataApi = downgradedApi(report.resolve(".jvmdg"), 44 + java);
        Path metadataTool = getDowngrader().getSingleFile().toPath();
        DowngradedMetadata.Result metadata = DowngradedMetadata.relocate(out, metadataApi, metadataTool, prefix, java);
        JsonObject metadataReport = new JsonObject();
        metadataReport.addProperty("checked", true);
        metadataReport.addProperty("apiJarSha256", PrivateLibraries.sha256(metadataApi));
        metadataReport.addProperty("pinnedToolJarSha256", PrivateLibraries.sha256(metadataTool));
        metadataReport.addProperty("targetClassMajor", 44 + java);
        metadataReport.addProperty("relocatedClasses", metadata.classes());
        metadataReport.addProperty("pinnedToolClosureClassMajorMaximum", metadata.toolClassMajorMaximum());
        JsonArray metadataClasses = new JsonArray();
        metadata.names().forEach(metadataClasses::add);
        metadataReport.add("classes", metadataClasses);
        JsonArray artifactClasses = new JsonArray();
        metadata.artifactClasses().forEach(artifactClasses::add);
        metadataReport.add("artifactClasses", artifactClasses);
        JsonArray apiClasses = new JsonArray();
        metadata.apiClasses().forEach(apiClasses::add);
        metadataReport.add("apiClasses", apiClasses);
        JsonArray toolClasses = new JsonArray();
        metadata.toolClasses().forEach(toolClasses::add);
        metadataReport.add("pinnedToolClasses", toolClasses);
        r.add("backportMetadata", metadataReport);
        r.addProperty("classVersionAfter", maxMajor(out));
        r.addProperty("downgraded", true);
        r.addProperty("classesSha256", Fingerprint.ofDirectory(out));

        if (maxMajor(out) > 44 + java) {
            write(report, r);
            throw new GradleException("Mycelium: " + out.getFileName() + " still holds class version " + maxMajor(out)
                    + ", more than Java " + java + " runs (" + log + ")");
        }

        List<String> stubs = Linkage.missingStubs(out, List.of(prefix, getShippedShadePrefix().get()), false);
        JsonArray s = new JsonArray();
        stubs.forEach(s::add);
        r.add("missingStubs", s);
        if (!stubs.isEmpty()) {
            write(report, r);
            throw new GradleException("Mycelium: no Java " + java + " backport for these calls of " + in.getFileName()
                    + " (the first call throws):\n  " + String.join("\n  ", stubs));
        }

        Map<String, String> javaRefs = new LinkedHashMap<>();
        Linkage.outsideReferences(out).forEach((ref, where) -> {
            String owner = ref.substring(ref.indexOf(' ') + 1);
            if (owner.startsWith("java/") || owner.startsWith("javax/")) {
                javaRefs.put(ref, where);
            }
        });
        Path linkLog = report.resolve("linkage.log");
        int unresolved = Linkage.probe(javaRefs, report, getLauncher().get().getExecutablePath().getAsFile().getPath(), linkLog);
        r.addProperty("javaReferences", javaRefs.size());
        r.addProperty("unresolved", unresolved);
        write(report, r);
        if (unresolved != 0) {
            List<String> lines = Files.readAllLines(linkLog, StandardCharsets.UTF_8).stream()
                    .filter(l -> l.startsWith("UNRESOLVED")).limit(20).collect(Collectors.toList());
            throw new GradleException("Mycelium: " + unresolved + " Java reference(s) of " + out.getFileName() + " are missing on Java "
                    + java + ":\n  " + String.join("\n  ", lines));
        }
        getLogger().lifecycle("Mycelium: {} downgraded to Java {} (class version {} to {}; {} Java references resolve on it)",
                in.getFileName(), java, r.get("classVersionBefore").getAsInt(), r.get("classVersionAfter").getAsInt(), javaRefs.size());
    }

    /** Highest class file major version in a jar (52 is Java 8), or 0 when it holds no class. */
    static int maxMajor(Path jar) throws IOException {
        int max = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/versions/")) {
                    byte[] head = zip.readNBytes(8);
                    if (head.length == 8) {
                        max = Math.max(max, ((head[6] & 0xff) << 8) | (head[7] & 0xff));
                    }
                }
            }
        }
        return max;
    }

    private static Path downgradedApi(Path directory, int targetMajor) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new GradleException("Mycelium: JvmDowngrader did not produce its target API archive in " + directory);
        }
        List<Path> matches;
        try (var files = Files.list(directory)) {
            matches = files.filter(path -> path.getFileName().toString().endsWith("-downgraded-" + targetMajor + ".jar"))
                    .sorted().toList();
        }
        if (matches.size() != 1) {
            throw new GradleException("Mycelium: expected one JvmDowngrader API archive downgraded to class version "
                    + targetMajor + " in " + directory + ", found " + matches.size());
        }
        return matches.get(0);
    }

    private void write(Path dir, JsonObject report) throws IOException {
        Files.writeString(dir.resolve("downgrade.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardCharsets.UTF_8);
    }

    /** JvmDowngrader, once more if it fails: 2.0.1 races in its shading (a shared HashMap across threads), see EraTask. */
    private boolean tool(Path dir, Path log, String... command) throws IOException, InterruptedException {
        if (exec(dir, log, command) == 0) {
            return true;
        }
        Files.writeString(log, "\n[mycelium] JvmDowngrader failed; retrying once\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        getLogger().warn("Mycelium: JvmDowngrader failed once on {}; retrying", getInput().get().getAsFile().getName());
        return exec(dir, log, command) == 0;
    }

    private static int exec(Path dir, Path log, String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        return p.waitFor();
    }
}
