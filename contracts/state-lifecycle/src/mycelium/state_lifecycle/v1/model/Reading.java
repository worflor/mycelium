package mycelium.state_lifecycle.v1.model;

import java.util.Objects;

/**
 * What an access to a slot on a host finds. The cases are never conflated: a host that is gone is not a host without a
 * value, and a value that is absent is not the default.
 */
public sealed interface Reading<T> permits Reading.Gone, Reading.Absent, Reading.Present, Reading.Held {
    /** The ref's incarnation is not current: there is no such host to ask. */
    record Gone<T>() implements Reading<T> {
    }

    /** The host is live and holds no value ({@code peek} only). */
    record Absent<T>() implements Reading<T> {
    }

    /** The value: present, or for {@code read} the declared initial value of an absent one, or just created. */
    record Present<T>(T value) implements Reading<T> {
        public Present {
            Objects.requireNonNull(value, "value");
        }
    }

    /** An EXCLUSIVE slot's value already exists on another host, so none is created here ({@code materialize}, {@code write}). */
    record Held<T>(HostId holder) implements Reading<T> {
    }

    default boolean isGone() {
        return this instanceof Gone;
    }

    default boolean isAbsent() {
        return this instanceof Absent;
    }

    /** The value when {@code Present}, otherwise {@code null}. */
    default T valueOrNull() {
        return this instanceof Present<T> p ? p.value() : null;
    }
}
