package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Broken fixture: throws unchecked runtime exceptions when encountering malformed or corrupted nodes.
 * Violates Law T5 (Diagnostic Rejection on Corruption).
 */
public final class CrashingParseTranslator implements RosettaTranslator {
    @Override
    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot, ComponentRegistry registry) {
        return ReferenceTranslator.create().toLegacyNbt(snapshot, registry);
    }

    @Override
    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt, ComponentRegistry registry) {
        Optional<TagNode.TagCompound> fencedOpt = nbt.getCompound(FENCED_NAMESPACE);
        if (fencedOpt.isEmpty()) {
            return CarrierSnapshot.empty();
        }

        TagNode.TagCompound fenced = fencedOpt.get();
        for (Map.Entry<String, TagNode> entry : fenced.entries().entrySet()) {
            ComponentId id = ComponentId.of(entry.getKey());
            Optional<ComponentDefinition<?>> defOpt = registry.get(id);
            if (defOpt.isPresent()) {
                try {
                    defOpt.get().decodeValue(entry.getValue());
                } catch (CodecException e) {
                    // Broken: throws unchecked exception rather than handling diagnostic rejection
                    throw new IllegalStateException("Corrupt component: " + entry.getKey(), e);
                }
            }
        }

        return ReferenceTranslator.create().fromLegacyNbt(nbt, registry);
    }
}
