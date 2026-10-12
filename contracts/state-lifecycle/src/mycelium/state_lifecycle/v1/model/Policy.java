package mycelium.state_lifecycle.v1.model;

import java.util.Objects;
import java.util.function.Function;

/**
 * What a slot does at one transition. A policy is declared per transition; there is no default.
 *
 * <p>The words mean the same everywhere, applied to the shape of the transition (see {@link Shape#admits}):
 * <ul>
 *   <li>{@code KEEP}: the value stays as it is. At a save, a durable value is written; at a begin, a durable value is
 *       loaded; at an end, a durable value's live copy is released and storage keeps it; at a succession it moves to
 *       the new incarnation.</li>
 *   <li>{@code DROP}: the value ceases to exist for that host, live and stored.</li>
 *   <li>{@code RESET}: the value becomes the slot's declared initial value.</li>
 *   <li>{@code MIGRATE(f)}: the new value is {@code f(old)}, where there is an old one.</li>
 *   <li>At a fork only: {@code COPY} (the new host gets an equal value, both keep theirs), {@code REGENERATE(g)} (the
 *       new host gets {@code g(its id)}, independent of the first), {@code EXCLUSIVE} (the value moves: it exists on
 *       exactly one of the two hosts afterwards).</li>
 * </ul>
 * Functions must be pure and must not mutate their argument: a value held by a slot is immutable.
 */
public final class Policy<T> {
    public enum Kind { KEEP, DROP, RESET, MIGRATE, COPY, REGENERATE, EXCLUSIVE }

    private final Kind kind;
    private final Function<T, T> migrate;
    private final Function<HostId, T> regenerate;

    private Policy(Kind kind, Function<T, T> migrate, Function<HostId, T> regenerate) {
        this.kind = kind;
        this.migrate = migrate;
        this.regenerate = regenerate;
    }

    public static <T> Policy<T> keep() {
        return new Policy<>(Kind.KEEP, null, null);
    }

    public static <T> Policy<T> drop() {
        return new Policy<>(Kind.DROP, null, null);
    }

    public static <T> Policy<T> reset() {
        return new Policy<>(Kind.RESET, null, null);
    }

    public static <T> Policy<T> migrate(Function<T, T> f) {
        return new Policy<>(Kind.MIGRATE, Objects.requireNonNull(f, "f"), null);
    }

    public static <T> Policy<T> copy() {
        return new Policy<>(Kind.COPY, null, null);
    }

    public static <T> Policy<T> regenerate(Function<HostId, T> g) {
        return new Policy<>(Kind.REGENERATE, null, Objects.requireNonNull(g, "g"));
    }

    public static <T> Policy<T> exclusive() {
        return new Policy<>(Kind.EXCLUSIVE, null, null);
    }

    public Kind kind() {
        return kind;
    }

    T migrated(T old) {
        return migrate.apply(old);
    }

    T regenerated(HostId host) {
        return regenerate.apply(host);
    }

    @Override
    public String toString() {
        return kind.name();
    }
}
