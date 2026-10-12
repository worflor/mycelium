package mycelium.state_lifecycle.v1.model;

/** The name of a slot: {@code namespace:path}. */
public record SlotId(String value) implements Comparable<SlotId> {
    public SlotId {
        Ids.check("slot id", value);
    }

    @Override
    public int compareTo(SlotId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
