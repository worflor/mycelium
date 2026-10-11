package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure declarative recipe for projecting semantic components into native NBT fields
 * below the compound level (e.g. display.Name, HideFlags bitfields), and reconciling
 * external native modifications against the emitted baseline: restoreEdited(S0, N0, N1).
 */
public interface NativeProjectionRecipe {
    Set<ComponentId> semanticInputs();

    TagNode.TagCompound project(CarrierSnapshot semanticState, TagNode.TagCompound currentNativeRoot);

    CarrierSnapshot restoreEdited(CarrierSnapshot s0, TagNode.TagCompound n0, TagNode.TagCompound n1, ComponentRegistry registry);

    static int applyBitmask(int oldFlags, int ownedMask, int requestedBits) {
        return (oldFlags & ~ownedMask) | (requestedBits & ownedMask);
    }

    /**
     * Built-in recipe for minecraft:custom_name <-> display.Name
     */
    static NativeProjectionRecipe displayName() {
        final ComponentId nameId = ComponentId.of("minecraft:custom_name");

        return new NativeProjectionRecipe() {
            @Override
            public Set<ComponentId> semanticInputs() {
                return Collections.singleton(nameId);
            }

            @Override
            public TagNode.TagCompound project(CarrierSnapshot semanticState, TagNode.TagCompound currentNativeRoot) {
                Objects.requireNonNull(semanticState, "semanticState");
                TagNode.TagCompound root = currentNativeRoot == null ? TagNode.TagCompound.empty() : currentNativeRoot;

                Optional<String> nameOpt = semanticState.getString(nameId);
                TagNode.TagCompound display = root.getCompound("display").orElse(TagNode.TagCompound.empty());

                Map<String, TagNode> displayEntries = new LinkedHashMap<>(display.entries());
                if (nameOpt.isPresent()) {
                    displayEntries.put("Name", TagNode.TagString.of(nameOpt.get()));
                } else if (semanticState.isRemoved(nameId)) {
                    displayEntries.remove("Name");
                }

                if (displayEntries.isEmpty()) {
                    return root.without("display");
                } else {
                    return root.with("display", new TagNode.TagCompound(displayEntries));
                }
            }

            @Override
            public CarrierSnapshot restoreEdited(CarrierSnapshot s0, TagNode.TagCompound n0, TagNode.TagCompound n1, ComponentRegistry registry) {
                Objects.requireNonNull(s0, "s0");
                Objects.requireNonNull(n1, "n1");

                // Start with s0 as foundation (preserves all unedited semantic & future fields)
                CarrierSnapshot.Builder builder = CarrierSnapshot.builder();
                for (Map.Entry<ComponentId, Object> e : s0.components().entrySet()) {
                    builder.with(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, TagNode> e : s0.unmappedTags().entrySet()) {
                    builder.withUnmappedTag(e.getKey(), e.getValue());
                }
                for (ComponentId rem : s0.removals()) {
                    builder.withRemoval(rem);
                }

                // Inspect difference between n0 (baseline) and n1 (observed)
                Optional<String> n0Name = n0 != null ? n0.getCompound("display").flatMap(d -> d.getString("Name")) : Optional.empty();
                Optional<String> n1Name = n1.getCompound("display").flatMap(d -> d.getString("Name"));

                if (!Objects.equals(n0Name, n1Name)) {
                    // A native change occurred on display.Name (e.g. legacy anvil rename)!
                    if (n1Name.isPresent()) {
                        builder.with(nameId, n1Name.get());
                    } else {
                        builder.withRemoval(nameId);
                    }
                }

                return builder.build();
            }
        };
    }
}
