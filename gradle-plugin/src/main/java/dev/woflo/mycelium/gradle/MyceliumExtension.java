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

    public void contracts(String... ids) {
        getContracts().addAll(ids);
    }
}
