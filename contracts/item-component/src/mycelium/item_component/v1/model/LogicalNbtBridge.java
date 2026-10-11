package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Representation bridge between modern logical NBT (which permits heterogeneous lists
 * in 1.21.5+ in-game interfaces) and target physical NBT (which strictly requires homogeneous
 * lists at the binary storage layer).
 * <p>
 * Separates:
 * <ul>
 *     <li>Logical component values (can be heterogeneous)</li>
 *     <li>Mojang 1.21.5 target-native physical NBT format: {@code [{'': 'a'}, {'b': 3}]}</li>
 *     <li>Rosetta transport format with unambiguous escape semantics</li>
 * </ul>
 */
public final class LogicalNbtBridge {
    public static final String MOJANG_WRAPPER_KEY = "";
    public static final String TRANSPORT_WRAPPER_KEY = "^w";
    public static final String TRANSPORT_TAG_KEY = "^t";

    private LogicalNbtBridge() {}

    /**
     * Bridges a list of logical TagNodes to a valid, physically homogeneous TagList conforming
     * to Mojang's native 1.21.5 physical representation: non-compound items in a heterogeneous
     * list are wrapped into a single-entry compound with empty key {@code ""}.
     */
    public static TagNode.TagList toNativeMojangList(List<TagNode> logicalNodes) {
        Objects.requireNonNull(logicalNodes, "logicalNodes");
        if (logicalNodes.isEmpty()) {
            return TagNode.TagList.of();
        }

        boolean homogeneous = true;
        byte firstType = logicalNodes.get(0).typeId();
        for (int i = 1; i < logicalNodes.size(); i++) {
            if (logicalNodes.get(i).typeId() != firstType) {
                homogeneous = false;
                break;
            }
        }

        if (homogeneous) {
            return new TagNode.TagList(logicalNodes);
        }

        List<TagNode> physicalElements = new ArrayList<>(logicalNodes.size());
        for (TagNode node : logicalNodes) {
            if (node instanceof TagNode.TagCompound tc) {
                physicalElements.add(tc);
            } else {
                physicalElements.add(TagNode.TagCompound.empty().with(MOJANG_WRAPPER_KEY, node));
            }
        }
        return new TagNode.TagList(physicalElements);
    }

    /**
     * Reconstructs logical TagNodes from a physical TagList adhering to Mojang 1.21.5 representation.
     */
    public static List<TagNode> fromNativeMojangList(TagNode.TagList physicalList) {
        Objects.requireNonNull(physicalList, "physicalList");
        if (physicalList.size() == 0) {
            return Collections.emptyList();
        }

        if (physicalList.elementTypeId() == 10) { // TagCompound
            boolean hasMojangWrappers = false;
            for (int i = 0; i < physicalList.size(); i++) {
                if (physicalList.get(i) instanceof TagNode.TagCompound tc && tc.entries().size() == 1 && tc.has(MOJANG_WRAPPER_KEY)) {
                    hasMojangWrappers = true;
                    break;
                }
            }

            if (hasMojangWrappers) {
                List<TagNode> logical = new ArrayList<>(physicalList.size());
                for (int i = 0; i < physicalList.size(); i++) {
                    TagNode node = physicalList.get(i);
                    if (node instanceof TagNode.TagCompound tc && tc.entries().size() == 1 && tc.has(MOJANG_WRAPPER_KEY)) {
                        logical.add(tc.get(MOJANG_WRAPPER_KEY));
                    } else {
                        logical.add(node);
                    }
                }
                return Collections.unmodifiableList(logical);
            }
        }

        return physicalList.elements();
    }

    /**
     * Converts a list of logical TagNodes to a physically homogeneous TagList.
     */
    public static TagNode.TagList toPhysicalList(List<TagNode> logicalNodes) {
        return toNativeMojangList(logicalNodes);
    }

    /**
     * Reconstructs logical TagNodes from a physical TagList, unwrapping compound wrappers if present.
     */
    public static List<TagNode> toLogicalList(TagNode.TagList physicalList) {
        return fromNativeMojangList(physicalList);
    }
}
