package mycelium.state_lifecycle.v1.model;

/**
 * Which runtime object of a host. A counter per host, advanced by every crossing that replaces the object (login,
 * respawn, load). The first incarnation is 1, so a zero in a record is never a real one.
 */
public record Incarnation(long value) implements Comparable<Incarnation> {
    public static final Incarnation FIRST = new Incarnation(1);

    public Incarnation {
        if (value < 1) {
            throw new IllegalArgumentException("incarnations start at 1, got " + value);
        }
    }

    public Incarnation next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException("incarnation counter exhausted");
        }
        return new Incarnation(value + 1);
    }

    @Override
    public int compareTo(Incarnation other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
