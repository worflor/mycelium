package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Representation bridge between modern logical NBT (which permits heterogeneous lists
 * in 1.21.5+ in-game interfaces) and target physical NBT (which strictly requires homogeneous
 * lists at the binary storage layer).
 */
public final class LogicalNbtBridge {
    public static final String WRAPPER_KEY = "\u00A7w";

    private LogicalNbtBridge() {}

    /**
     * Converts a list of logical TagNodes to a valid, physically homogeneous TagList.
     * If all nodes share the same typeId, emits them directly.
     * If heterogeneous, wraps each node into a compound wrapper: {@code { "\u00A7w": node }}.
     */
    public static TagNode.TagList toPhysicalList(List<TagNode> logicalNodes) {
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

        // Heterogeneous: wrap into homogeneous compound wrappers
        List<TagNode> wrapped = new ArrayList<>(logicalNodes.size());
        for (TagNode node : logicalNodes) {
            wrapped.add(TagNode.TagCompound.empty().with(WRAPPER_KEY, node));
        }
        return new TagNode.TagList(wrapped);
    }

    /**
     * Reconstructs the logical TagNode list from a physical TagList, unwrapping compound
     * wrappers if present.
     */
    public static List<TagNode> toLogicalList(TagNode.TagList physicalList) {
        Objects.requireNonNull(physicalList, "physicalList");
        if (physicalList.size() == 0) {
            return Collections.emptyList();
        }

        if (physicalList.elementTypeId() == 10) { // TagCompound
            boolean allWrapped = true;
            for (int i = 0; i < physicalList.size(); i++) {
                TagNode node = physicalList.get(i);
                if (!(node instanceof TagNode.TagCompound tc) || !tc.has(WRAPPER_KEY)) {
                    allWrapped = false;
                    break;
                }
            }

            if (allWrapped) {
                List<TagNode> unbridged = new ArrayList<>(physicalList.size());
                for (int i = 0; i < physicalList.size(); i++) {
                    TagNode.TagCompound tc = (TagNode.TagCompound) physicalList.get(i);
                    unbridged.add(tc.get(WRAPPER_KEY));
                }
                return Collections.unmodifiableList(unbridged);
            }
        }

        return physicalList.elements();
    }
}
