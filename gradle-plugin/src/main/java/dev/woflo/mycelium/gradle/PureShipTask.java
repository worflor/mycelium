package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Puts the pure layer's classes into this target's jar (docs/plugin.md, "Shipping downgraded jars"): the same bytes
 * {@code myceliumEraJava<N>} already downgraded, shaded and checked (stubs, linkage, behaviour) for this target's era,
 * when the era's Java is older than the pure layer's release; otherwise a no-op, because the classes as compiled
 * already run there (docs/decisions/0006).
 *
 * <p>This task never recompiles or re-downgrades anything: it locates the exact jar the era gate for this target's
 * Java already produced for the pure bundle ({@code build/mycelium/eras/java<N>/<slug>/java<N>.jar}, or
 * {@code modern.jar} when no downgrade happened), and copies out only the classes in the packages this artifact
 * declared as its pure layer (its {@code pure.sources} plus every imported contract model). The bundle also carries
 * the shared Mycelium harness and this artifact's test suites (needed to run the era gate's behaviour checks); those
 * never ship (Hard rule 2).
 */
public abstract class PureShipTask extends DefaultTask {
    /** The declared pure sources ({@code mycelium.pure.sources}): together with the imported models, what ships. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPureSources();

    /** Where imported contract models were generated: they join the pure layer (docs/eras.md). */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getImportedSources();

    /** The Java the pure layer is written for ({@code mycelium.pure.release}). */
    @Input
    public abstract Property<Integer> getSourceRelease();

    /** This target's own era Java ({@code mycelium.javaFor(minecraft)}). */
    @Input
    public abstract Property<Integer> getTargetJava();

    @Input
    public abstract Property<String> getShadePrefix();

    @Input
    public abstract Property<String> getDowngraderVersion();

    /** The pure bundle's name, as {@code myceliumPureCompile} wrote it ({@code bundle.properties}). */
    @Input
    public abstract Property<String> getBundleName();

    /**
     * {@code build/mycelium/eras/java<N>}: where the era gate for this target's Java left its jar and report, as the
     * path this task reads from. Its contents are tracked by {@link #getEraOutputs()}.
     */
    @Internal
    public abstract DirectoryProperty getEraReportDir();

    /**
     * The same folder's files, as an input: what ships is copied from the era gate's jar, so a change in that jar
     * must re-run this task, not only the gate running first. Set only when this target needs a downgrade.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getEraOutputs();

    /** Class files only: what a consumer's {@code jar} task adds, nothing else. */
    @OutputDirectory
    public abstract DirectoryProperty getClassesDir();

    /** {@code ship.json}: what shipped, from what, and that the era gate checked exactly those bytes. */
    @OutputDirectory
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException {
        Path classesOut = getClassesDir().get().getAsFile().toPath();
        Harness.deleteRecursively(classesOut);
        Files.createDirectories(classesOut);
        Path reportOut = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(reportOut);

        int sourceRelease = getSourceRelease().get();
        int targetJava = getTargetJava().get();

        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("sourceRelease", sourceRelease);
        report.addProperty("targetJava", targetJava);
        report.addProperty("shadePrefix", getShadePrefix().get());

        if (targetJava >= sourceRelease) {
            // No downgrade needed: the classes as compiled for `release` already run on this era's Java. Shipping
            // them a second time here would only risk a duplicate of whatever path the consumer already uses (their
            // own main sourceSet, if they added the pure sources to it themselves) — so this is a genuine no-op, and
            // says so, rather than a check with nothing to report (docs/admission.md, "a plugin task or option").
            report.addProperty("downgraded", false);
            report.addProperty("why", "target Java " + targetJava + " already runs release " + sourceRelease
                    + "; the pure layer ships as compiled, through whatever path already puts it in this jar");
            report.addProperty("classesShipped", 0);
            write(reportOut, report);
            getLogger().lifecycle("Mycelium: pure layer needs no downgrade for Java {} (release {}); myceliumPureShip is a no-op",
                    targetJava, sourceRelease);
            return;
        }

        Set<String> shippable = scanPackages(getPureSources(), getImportedSources());
        if (shippable.isEmpty()) {
            throw new GradleException("Mycelium: myceliumPureShip has nothing to ship (no pure sources and no imported"
                    + " contract models found; check mycelium { pure { sources.from(...) } })");
        }

        if (!getEraReportDir().isPresent() || !Files.isDirectory(getEraReportDir().get().getAsFile().toPath())) {
            throw new GradleException("Mycelium: myceliumPureShip needs myceliumEraJava" + targetJava + "'s report (none found); "
                    + "it must run first (the plugin wires this automatically when mycelium.minecraft names an era older than"
                    + " mycelium.pure.release)");
        }
        Path eraDir = getEraReportDir().get().getAsFile().toPath();
        String slug = Harness.slug(getBundleName().get());
        Path bundleWork = eraDir.resolve(slug);
        Path downgradedJar = bundleWork.resolve("java" + targetJava + ".jar");
        Path modernJar = bundleWork.resolve("modern.jar");
        Path jar = Files.isRegularFile(downgradedJar) ? downgradedJar : modernJar;
        if (!Files.isRegularFile(jar)) {
            throw new GradleException("Mycelium: no era gate output for the pure bundle '" + getBundleName().get() + "' at "
                    + bundleWork + " (myceliumEraJava" + targetJava + " must run before myceliumPureShip)");
        }
        boolean downgraded = jar.equals(downgradedJar);

        int extracted = 0;
        int shaded = 0;
        String shadePrefix = getShadePrefix().get();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) {
                    continue;
                }
                if (!inShippablePackage(e.getName(), shippable, shadePrefix)) {
                    continue;
                }
                Path dest = classesOut.resolve(e.getName());
                Files.createDirectories(dest.getParent());
                try (InputStream in = jf.getInputStream(e)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                extracted++;
                if (downgraded) {
                    int slash = e.getName().lastIndexOf('/');
                    String pkg = slash < 0 ? "" : e.getName().substring(0, slash).replace('/', '.');
                    if (pkg.equals(shadePrefix) || pkg.startsWith(shadePrefix + ".")) {
                        shaded++;
                    }
                }
            }
        }
        if (extracted == 0) {
            throw new GradleException("Mycelium: the era gate's jar at " + jar + " has no classes in the declared pure"
                    + " packages " + shippable + "; myceliumPureShip would ship nothing (a bundle with the wrong name, or"
                    + " packages that changed since the era gate ran)");
        }

        report.addProperty("downgraded", downgraded);
        report.addProperty("downgraderVersion", getDowngraderVersion().get());
        report.addProperty("bundle", getBundleName().get());
        report.addProperty("sourceJar", jar.getFileName().toString());
        JsonArray pkgs = new JsonArray();
        shippable.forEach(pkgs::add);
        report.add("packages", pkgs);
        report.addProperty("classesShipped", extracted);
        report.addProperty("shadedSupportClasses", shaded);
        report.addProperty("classesSha256", Fingerprint.ofDirectory(classesOut));
        report.add("eraGate", eraGateEvidence(eraDir, getBundleName().get(), targetJava));
        write(reportOut, report);
        getLogger().lifecycle("Mycelium: pure layer shipped for Java {} ({} class(es) in {} package(s), downgraded={}, {} shaded"
                + " support class(es))", targetJava, extracted, shippable.size(), downgraded, shaded);
    }

    /**
     * Points at the era gate's own record for this exact bundle: the claim is only ever "these bytes were part of a
     * bundle whose stubs, linkage and behaviour myceliumEraJava{@code N} checked" (DESIGN §11 claims discipline), never
     * a fresh, independent check of the shipped subset alone.
     */
    static JsonObject eraGateEvidence(Path eraDir, String bundleName, int java) throws IOException {
        Path eraJson = eraDir.resolve("era.json");
        JsonObject out = new JsonObject();
        if (!Files.isRegularFile(eraJson)) {
            out.addProperty("checked", false);
            out.addProperty("why", "no era.json at " + eraJson);
            return out;
        }
        JsonObject era = JsonParser.parseString(Files.readString(eraJson, StandardCharsets.UTF_8)).getAsJsonObject();
        for (var element : era.getAsJsonArray("results")) {
            JsonObject r = element.getAsJsonObject();
            if (bundleName.equals(r.get("bundle").getAsString())) {
                out.addProperty("checked", true);
                out.addProperty("java", java);
                out.addProperty("status", r.has("status") ? r.get("status").getAsString() : "unknown");
                out.addProperty("bytecode", r.has("bytecode") ? r.get("bytecode").getAsString() : "unknown");
                if (r.has("missingStubs")) {
                    out.addProperty("missingStubs", r.getAsJsonArray("missingStubs").size());
                }
                out.addProperty("outsideReferences", r.has("outsideReferences") ? r.get("outsideReferences").getAsInt() : -1);
                out.addProperty("unresolved", r.has("unresolved") ? r.get("unresolved").getAsInt() : -1);
                out.addProperty("suites", r.has("suites") ? r.getAsJsonArray("suites").size() : 0);
                out.addProperty("note", "the shipped classes are an unmodified subset of this bundle's checked bytes;"
                        + " the bundle also carries the shared harness and test suites, which never ship (Hard rule 2)");
                return out;
            }
        }
        out.addProperty("checked", false);
        out.addProperty("why", "no result for bundle '" + bundleName + "' in " + eraJson);
        return out;
    }

    /**
     * Whether a jar entry's package (its directory, dotted) is one the artifact declared as pure, or one JvmDowngrader
     * shaded its own backport support classes under ({@code shadePrefix}, or {@code shadePrefix.*}): the downgraded
     * bytecode calls those directly (its stub check is exactly what proves it needs them), so shipping the pure
     * layer without them would run on this era's JVM only until the first call — {@code NoClassDefFoundError}, not the
     * {@code MissingStubError} the stub check would have named. They are Mycelium's own shading, not the pure layer's
     * source, so they never show up in {@code packages}, which only ever comes from a source scan.
     */
    static boolean inShippablePackage(String entryName, Set<String> packages, String shadePrefix) {
        int slash = entryName.lastIndexOf('/');
        String pkg = slash < 0 ? "" : entryName.substring(0, slash).replace('/', '.');
        return packages.contains(pkg) || pkg.equals(shadePrefix) || pkg.startsWith(shadePrefix + ".");
    }

    /**
     * Whether a class the artifact compiled itself makes way for what ship supplied: ship has the class, or has the
     * top-level class it is nested in. A nested or synthetic class ({@code Outer$1}, {@code Outer$Inner}) belongs to its
     * outermost class, and the two compilations of that class (the artifact's, for its own release, and the pure bundle's,
     * for the pure layer's) do not always make the same ones: an enum switch has a synthetic {@code Outer$1} on one and none
     * on the other. Left in the jar, the artifact's {@code Outer$1} would be a stale class of another compilation, at a
     * class version the era may not run, that nothing calls.
     *
     * @param shipClasses the directory ship wrote its classes to
     * @param entryPath a jar entry's path, e.g. {@code dev/woflo/Outer$1.class}
     */
    static boolean makesWay(Path shipClasses, String entryPath) {
        if (!entryPath.endsWith(".class")) {
            return false;
        }
        if (Files.isRegularFile(shipClasses.resolve(entryPath))) {
            return true;
        }
        int dollar = entryPath.indexOf('$', entryPath.lastIndexOf('/') + 1);
        return dollar > 0 && Files.isRegularFile(shipClasses.resolve(entryPath.substring(0, dollar) + ".class"));
    }

    /** Every package declared by the artifact's pure sources and its imported contract models, from the source. */
    static Set<String> scanPackages(ConfigurableFileCollection pureSources, ConfigurableFileCollection importedSources) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        List<Path> files = new ArrayList<>(Harness.javaFiles(PurityTask.paths(pureSources)));
        files.addAll(Harness.javaFiles(PurityTask.paths(importedSources)));
        for (Path p : files) {
            out.add(PurityTask.packageOf(Files.readString(p, StandardCharsets.UTF_8)));
        }
        return out;
    }

    private static void write(Path dir, JsonObject report) throws IOException {
        Files.writeString(dir.resolve("ship.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8);
    }
}
