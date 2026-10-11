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

                TagNode existingDisplayNode = root.get("display");
                if (existingDisplayNode != null && !(existingDisplayNode instanceof TagNode.TagCompound)) {
                    // Non-compound display node is malformed native data: never delete it!
                    return root;
                }

                Optional<String> nameOpt = semanticState.getString(nameId);
                TagNode.TagCompound display = root.getCompound("display").orElse(TagNode.TagCompound.empty());
                Map<String, TagNode> displayEntries = new LinkedHashMap<>(display.entries());

                if (nameOpt.isPresent()) {
                    displayEntries.put("Name", TagNode.TagString.of(nameOpt.get()));
                } else {
                    // Desired-state: absence, removal, or reset to inherit clears the owned display.Name field
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
                for (RecoveryRecord rec : s0.recoveryRecords()) {
                    builder.withRecovery(rec);
                }
                s0.rawEnvelope().ifPresent(builder::withRawEnvelope);
                for (Map.Entry<ComponentId, TagNode.TagCompound> res : s0.retainedResiduals().entrySet()) {
                    builder.withResidual(res.getKey(), res.getValue());
                }

                // Inspect baseline n0 Name
                Optional<String> n0Name = n0 != null ? n0.getCompound("display").flatMap(d -> d.getString("Name")) : Optional.empty();

                // Inspect observed n1 Name
                Optional<TagNode.TagCompound> n1Display = n1.getCompound("display");
                if (n1Display.isPresent()) {
                    TagNode rawName = n1Display.get().get("Name");
                    if (rawName instanceof TagNode.TagString ts) {
                        String n1NameStr = ts.value();
                        if (!Objects.equals(n0Name.orElse(null), n1NameStr)) {
                            // Legitimate native rename (e.g. anvil rename)
                            builder.with(nameId, n1NameStr);
                        }
                    } else if (rawName != null) {
                        // Malformed native name: do NOT interpret as removal!
                        // Preserve original semantic state and record a recovery record
                        builder.withRecovery(RecoveryRecord.of("display.Name", rawName,
                                RecoveryRecord.FailureCategory.MALFORMED_CONTROL_SYNTAX, nameId,
                                "Malformed non-string native display.Name: " + rawName.getClass().getSimpleName()));
                    } else {
                        // Name was deleted in native compound
                        if (n0Name.isPresent()) {
                            builder.withRemoval(nameId);
                        }
                    }
                } else if (n0Name.isPresent()) {
                    // display compound was completely deleted natively
                    builder.withRemoval(nameId);
                }

                return builder.build();
            }
        };
    }
}
