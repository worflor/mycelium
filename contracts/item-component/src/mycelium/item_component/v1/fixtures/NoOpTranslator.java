package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: does nothing, returning empty tags on encode and empty snapshots on decode.
 * Violates Law T1 (Round-Trip Isomorphism).
 */
public final class NoOpTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        return TagNode.TagCompound.empty();
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        return CarrierSnapshot.empty();
    }
}
