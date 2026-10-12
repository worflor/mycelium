package dev.woflo.mycelium.gradle;

import org.gradle.api.Action;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

import java.util.List;

/**
 * {@code mycelium { ... }} in a consumer build script.
 *
 * <pre>
 * mycelium {
 *     root = rootProject.file("../../mycelium")
 *     namespace = "com.example.mymod.mycelium"
 *     contracts("world.copy@1", "world.copy@1")
 *     targetProfile = "26.3-fabric"
 * }
 * </pre>
 */
public abstract class MyceliumExtension {
    /** The Mycelium checkout (contains {@code contracts/registry.json} and {@code harness/src}). */
    public abstract DirectoryProperty getRoot();

    /** Artifact-owned package prefix that imported models are moved under (DESIGN §12 packaging rule). */
    public abstract Property<String> getNamespace();

    /** Contract ids to import, e.g. {@code world.copy@1}. Empty means verify-all, import-none. */
    public abstract ListProperty<String> getContracts();

    /** Run every selected contract's harness as part of {@code check}. Default true. */
    public abstract Property<Boolean> getVerify();

    /** Free-form target profile label recorded in the evidence manifest, e.g. {@code 26.3-fabric}. */
    public abstract Property<String> getTargetProfile();

    /** Artifact id recorded in the evidence manifest (defaults to the project name). */
    public abstract Property<String> getArtifactId();

    /** Artifact version recorded in the evidence manifest (defaults to the project version). */
    public abstract Property<String> getArtifactVersion();

    /** Jar task names receiving this artifact's private code and evidence. Empty preserves attachment to every Jar. */
    public abstract ListProperty<String> getArtifactJars();

    public void artifactJars(String... taskNames) {
        getArtifactJars().addAll(taskNames);
    }

    /** Fail the build when an imported contract is never referenced by the artifact's own sources. Default false (warn). */
    public abstract Property<Boolean> getFailOnUnused();

    /**
     * Every Java the pure layer (contract models, harnesses) must run on, checked by {@code myceliumEras}: one JVM per
     * Minecraft era (docs/eras.md), e.g. {@code eras(8, 16, 17, 21, 25)} for every version there is. Empty means no check.
     */
    public abstract ListProperty<Integer> getEras();

    /** The JvmDowngrader release {@code myceliumEras} uses. */
    public abstract Property<String> getDowngraderVersion();

    public void eras(Integer... javaVersions) {
        getEras().addAll(javaVersions);
    }

    /** Every Java some Minecraft era runs on ({@code eras/minecraft.json}): what a framework proves itself on. */
    public void allEras() {
        getEras().set(getRoot().map(dir -> Eras.load(dir.getAsFile()).allJavas()));
    }

    /**
     * The Minecraft version this project targets ({@code 26.3}, {@code 1.21.1}, {@code b1.7.3}). Its era
     * ({@code eras/minecraft.json}) sets the era gate when no {@code eras(...)} are declared, and is recorded in the
     * evidence manifest.
     */
    public abstract Property<String> getMinecraft();

    /** The Java a Minecraft version runs on, from the era table: for a target's toolchain. Needs {@code root} set. */
    public int javaFor(String minecraft) {
        return Eras.load(getRoot().get().getAsFile()).of(minecraft).java();
    }

    /**
     * The Java feature version of the newest class file {@code loader}'s scanner reads on {@code minecraft}, from the era
     * table: what a capsule's {@code outerJava} is set to. Needs {@code root} set.
     */
    public int outerJavaFor(String loader, String minecraft) {
        return Eras.load(getRoot().get().getAsFile()).of(minecraft).outerJavaFor(loader, minecraft);
    }

    /**
     * The Minecraft versions this artifact says it runs on and what proves it (docs/decisions/0009): read from the
     * consumer's ledger or written here, held by {@code myceliumRange} to the era table, the proofs and the loader's own
     * metadata, and recorded in the evidence manifest.
     */
    @Nested
    public abstract RangeSpec getRange();

    /**
     * Where the consumer keeps its item presentation programs (docs/decisions/0012); the defaults are the layout
     * {@code src/main/presentation/}, so most builds declare nothing.
     */
    @Nested
    public abstract PresentationSpec getPresentation();

    public void presentation(Action<? super PresentationSpec> action) {
        action.execute(getPresentation());
    }

    public void range(Action<? super RangeSpec> action) {
        action.execute(getRange());
    }

    /**
     * The declared range as the loader writes it in the metadata the artifact ships (a Fabric {@code depends.minecraft},
     * a Forge {@code versionRange}, a plugin's {@code api-version}), so what the loader enforces is the claim and not a
     * second statement of it; {@code null} where the loader has nothing to say (a plugin before 1.13 names no API
     * version). Needs {@code root} and a range.
     */
    public String compat() {
        ArtifactLedger.Artifact a = getRange().artifact();
        Loaders.Loader loader = Loaders.load(getRoot().get().getAsFile()).loaders().get(a.loader());
        if (loader == null || loader.compat() == null) {
            throw new IllegalStateException("the loader '" + a.loader() + "' declares no compatibility the build can write (eras/loaders.json)");
        }
        return CompatExpr.render(loader.compat().syntax(), a.from(), a.to());
    }

    /** A version the ledger pins for the artifact's own node (a loader's API, say); a miss names what it does pin. */
    public String pin(String name) {
        ArtifactLedger.Artifact a = getRange().artifact();
        String value = a.pinsFor(a.node()).get(name);
        if (value == null) {
            throw new IllegalArgumentException("the ledger pins no '" + name + "' for " + a.id() + " at " + a.node()
                    + "; it pins " + a.pinsFor(a.node()).keySet() + ". Add it to the row's pins.");
        }
        return value;
    }

    /** The Java versions the era gates run: {@code eras(...)} if declared, else the target Minecraft's own era. */
    public List<Integer> resolvedEras() {
        if (!getEras().get().isEmpty()) {
            return getEras().get();
        }
        if (getMinecraft().isPresent()) {
            return List.of(javaFor(getMinecraft().get()));
        }
        return List.of();
    }

    /** Package prefix for the downgrader's shaded Java API backports; unique per artifact so two mods never collide. */
    public abstract Property<String> getShadePrefix();

    /** Feature library package relocations; the jars are declared in the myceliumPrivateLibraries configuration. */
    public abstract ListProperty<String> getLibraryRelocations();

    public void privateLibrary(String fromPackage, String ownedPackage) {
        getLibraryRelocations().add(new PrivateLibraries.Relocation(fromPackage, ownedPackage).encode());
    }

    /** The artifact's pure layer: the code the same on every version and loader (docs/eras.md). */
    @Nested
    public abstract PureSpec getPure();

    public void pure(Action<? super PureSpec> action) {
        action.execute(getPure());
    }

    public void contracts(String... ids) {
        getContracts().addAll(ids);
    }

    /** Declared optional integrations (encoded), checked by {@code myceliumIsolation}. */
    public abstract ListProperty<String> getIntegrations();

    /**
     * An optional integration with another mod: only {@code homePackage} may mention {@code apiPackages}, and nothing
     * outside it may mention {@code homePackage}. Use {@link #integration(String, String, String, String)} to name the
     * one gate file (relative to a source root) that checks the mod is loaded and calls in.
     */
    public void integration(String name, String homePackage, String apiPackages) {
        integration(name, homePackage, apiPackages, "");
    }

    /** As above, with {@code gates}: comma-separated source paths allowed to reach into the home package. */
    public void integration(String name, String homePackage, String apiPackages, String gates) {
        // validated here, as written, so a mistake is named at the line that made it
        getIntegrations().add(new IsolationTask.Integration(name, homePackage, IsolationTask.Integration.split(apiPackages),
                IsolationTask.Integration.split(gates)).encode());
    }

    /**
     * Packages of this artifact's own sources that are its ports (L4, DESIGN §5): expected to touch the platform.
     * {@code myceliumSurface} reports every other package's platform references separately, as the surface the next
     * rung would have to eat. Not validated against the platform packages or against what exists on disk: a port that
     * turns out to reference nothing yet is not a mistake, and one misspelled just reads as "not a port" rather than
     * failing the build (docs/admission.md: a new option cannot break a build that did not ask for it).
     */
    public abstract ListProperty<String> getPorts();

    public void ports(String... packages) {
        getPorts().addAll(packages);
    }

    /** Fail {@code myceliumSurface} when the non-port surface grows past {@link #getSurfaceBaseline()}. Default false. */
    public abstract Property<Boolean> getSurfaceFailOnGrowth();

    /** A checked-in {@code surface.json} (schema in {@link SurfaceTask}) to compare against. Optional. */
    public abstract RegularFileProperty getSurfaceBaseline();

    /** Optional scanner/runtime linkage profile for a capsule artifact; absent for ordinary jars. */
    @Nested
    public abstract CapsuleSpec getCapsule();

    public void capsule(Action<? super CapsuleSpec> action) {
        action.execute(getCapsule());
    }
}
