package mycelium.item_component.v1.model;

import java.util.Objects;
import java.util.Optional;

/**
 * What one component of a stack's compound is, read by the same path as the whole snapshot ({@link FencedCarrier#read}).
 * Four things it can be, and a quarantined entry is never confused with an absent one (T15).
 *
 * @param <T> the component's value type
 */
public sealed interface ComponentRead<T> {
    /** The value, when present. */
    Optional<T> value();

    /** Carried, with a value. For a presence component this is whether the mark is there. */
    default boolean isPresent() {
        return this instanceof Present<?>;
    }

    /** Explicitly removed (a negative patch). */
    default boolean isRemoved() {
        return this instanceof Removed<?>;
    }

    /** Something is stored for it but cannot be read; it is kept, not lost (T5). */
    default boolean isQuarantined() {
        return this instanceof Quarantined<?>;
    }

    default T orElse(T fallback) {
        return value().orElse(fallback);
    }

    /** The component is carried with this value. */
    record Present<T>(T carried) implements ComponentRead<T> {
        public Present {
            Objects.requireNonNull(carried, "carried");
        }

        @Override
        public Optional<T> value() {
            return Optional.of(carried);
        }
    }

    /** Nothing is stored for the component. */
    record Absent<T>() implements ComponentRead<T> {
        @Override
        public Optional<T> value() {
            return Optional.empty();
        }
    }

    /** The component was explicitly removed. */
    record Removed<T>() implements ComponentRead<T> {
        @Override
        public Optional<T> value() {
            return Optional.empty();
        }
    }

    /**
     * Something is stored but unreadable: the component's own entry, an old key of a migration, or the whole fenced
     * envelope (then {@code record().category()} is {@code UNREADABLE_ENVELOPE}). The raw evidence is {@code record().rawNode()}.
     */
    record Quarantined<T>(RecoveryRecord record) implements ComponentRead<T> {
        public Quarantined {
            Objects.requireNonNull(record, "record");
        }

        @Override
        public Optional<T> value() {
            return Optional.empty();
        }
    }
}
