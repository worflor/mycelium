package dev.woflo.mycelium.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/** Declarative runtime profile for a loader-scanned capsule and its nested payload jars. */
public abstract class CapsuleSpec {
    /** Jar task output checked by {@code myceliumCapsule}. */
    public abstract RegularFileProperty getArchive();

    /** Maximum Java feature version accepted in the outer archive. */
    public abstract Property<Integer> getOuterJava();

    /** Ordered runtime classpath roots, including the exact game, loader, libraries, and Java API archives needed. */
    public abstract ConfigurableFileCollection getRuntimeArtifacts();

    /** Optional Tiny v2 mapping archive used to reject retained source-namespace class names. */
    public abstract RegularFileProperty getMappings();

    /** Tiny namespace names, from source names to final runtime names. */
    public abstract Property<String> getMappingFrom();
    public abstract Property<String> getMappingTo();

    /** Internal-name prefixes whose source mappings are checked in the final bytecode. */
    public abstract ListProperty<String> getMappedPrefixes();

    /** Nested jar entries encoded as {@code entry|javaFeature}; configure with {@link #nestedJar(String, int)}. */
    public abstract ListProperty<String> getNestedJars();

    public void nestedJar(String entry, int javaFeature) {
        if (entry == null || entry.isBlank() || entry.startsWith("/") || entry.contains("..") || javaFeature < 1) {
            throw new IllegalArgumentException("Mycelium capsule nestedJar requires a safe archive entry and positive Java feature version");
        }
        getNestedJars().add(entry + "|" + javaFeature);
    }

    public void runtimeArtifacts(Object... artifacts) {
        getRuntimeArtifacts().from(artifacts);
    }

    public void mappingNamespaces(String from, String to) {
        getMappingFrom().set(from);
        getMappingTo().set(to);
    }
}
