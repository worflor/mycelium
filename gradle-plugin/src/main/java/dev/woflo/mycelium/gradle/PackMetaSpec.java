package dev.woflo.mycelium.gradle;

import org.gradle.api.provider.Property;

/**
 * {@code mycelium { packMeta { ... } }}: have the plugin write this target's {@code pack.mcmeta} into the resources
 * (docs/decisions/0017). Declaring the block turns it on; the formats and the schema come from the era table.
 *
 * <pre>
 * mycelium { packMeta { description = "Somnium resources" } }   // Forge only: Fabric and NeoForge make their own
 * </pre>
 */
public abstract class PackMetaSpec {
    /** The pack's description. Defaults to the artifact id plus " resources". */
    public abstract Property<String> getDescription();

    /** Turn it on without a description (the default one is used). Declaring a description turns it on too. */
    public abstract Property<Boolean> getEnabled();

    public boolean isOn() {
        return getEnabled().getOrElse(false) || getDescription().isPresent();
    }
}
