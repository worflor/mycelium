package mycelium.item_component.v1.model;

import java.util.Objects;

/**
 * The typed result of a write onto a stack's compound ({@link FencedCarrier}): it was written, or it was refused with a
 * reason the adapter can act on. A write that changes nothing because of quarantined data is never a silent no-op (T14).
 */
public sealed interface WriteOutcome {
    /** The compound after the write; for a refusal, the compound as it was given, untouched. */
    TagNode.TagCompound root();

    default boolean isWritten() {
        return this instanceof Written;
    }

    /** The write was applied. {@code changed} says whether {@code root} differs from what was given (false: nothing to do). */
    record Written(TagNode.TagCompound root, boolean changed) implements WriteOutcome {
        public Written {
            Objects.requireNonNull(root, "root");
        }
    }

    /** The write was not applied; {@code root} is the compound as given. */
    record Refused(Reason reason, String detail, TagNode.TagCompound root) implements WriteOutcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
            Objects.requireNonNull(root, "root");
        }
    }

    enum Reason {
        /** The fenced entry is not a compound; applying the write would move or discard it. */
        ENVELOPE_QUARANTINED,
        /** A component entry (or an old key) is stored but unreadable; applying the write would drop it. */
        COMPONENT_QUARANTINED
    }
}
