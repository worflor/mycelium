package mycelium.item_component.v1.model;

/**
 * Pure bidirectional translator between typed {@link CarrierSnapshot} component states
 * and legacy hierarchical {@link TagNode.TagCompound} NBT trees.
 */
public interface RosettaTranslator {
    /** The reserved top-level NBT compound key for fenced component payloads. */
    String FENCED_NAMESPACE = "mycelium$c";

    /**
     * Translates a carrier snapshot into a legacy NBT compound.
     * All component entries are fenced under {@link #FENCED_NAMESPACE}.
     * Values matching their registered default are elided (Law T2).
     *
     * @param snapshot the carrier snapshot
     * @param registry the registry of known component definitions
     * @return the legacy NBT compound with components fenced under {@link #FENCED_NAMESPACE}
     */
    TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry);

    /**
     * Translates a legacy NBT compound back into a typed carrier snapshot.
     * Unrecognized tags under {@link #FENCED_NAMESPACE} are preserved as unmapped tags (Law T4).
     * Corrupted or unparseable tags are rejected with structured diagnostics (Law T5).
     *
     * @param nbt the legacy NBT compound
     * @param registry the registry of known component definitions
     * @return the deserialized carrier snapshot
     */
    CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry);

    /** Returns the standard reference implementation of the translator. */
    static RosettaTranslator standard() {
        return StandardRosettaTranslator.INSTANCE;
    }
}

