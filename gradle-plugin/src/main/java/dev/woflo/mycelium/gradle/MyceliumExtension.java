package dev.woflo.mycelium.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

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

    /** Fail the build when an imported contract is never referenced by the artifact's own sources. Default false (warn). */
    public abstract Property<Boolean> getFailOnUnused();

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
        getIntegrations().add(name + "|" + homePackage + "|" + apiPackages + "|" + gates);
    }
}
