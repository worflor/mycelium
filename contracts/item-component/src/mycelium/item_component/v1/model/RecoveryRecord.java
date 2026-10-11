package mycelium.item_component.v1.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Structured record preserving recovery evidence from unreadable envelopes,
 * corrupt payloads, malformed control syntax, or alias collisions without
 * competing with active component syntax.
 */
public record RecoveryRecord(
        String location,
        TagNode rawNode,
        FailureCategory category,
        Optional<ComponentId> componentId,
        String diagnosticMessage
) {
    public enum FailureCategory {
        UNREADABLE_ENVELOPE,
        MALFORMED_CONTROL_SYNTAX,
        CORRUPT_PAYLOAD,
        UNREGISTERED_COMPONENT,
        ALIAS_COLLISION
    }

    public RecoveryRecord {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(rawNode, "rawNode");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(componentId, "componentId");
        Objects.requireNonNull(diagnosticMessage, "diagnosticMessage");
    }

    public static RecoveryRecord of(String location, TagNode rawNode, FailureCategory category, String diagnosticMessage) {
        return new RecoveryRecord(location, rawNode, category, Optional.empty(), diagnosticMessage);
    }

    public static RecoveryRecord of(String location, TagNode rawNode, FailureCategory category, ComponentId id, String diagnosticMessage) {
        return new RecoveryRecord(location, rawNode, category, Optional.of(id), diagnosticMessage);
    }
}
