package mycelium.world_clock.v1.model;

/** A clock's identity (26.x: {@code minecraft:overworld}). A clock is nothing but this; its meaning is its timelines'. */
public record ClockId(String id) {
    public ClockId {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a clock needs an id");
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
