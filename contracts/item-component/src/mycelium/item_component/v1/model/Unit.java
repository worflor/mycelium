package mycelium.item_component.v1.model;

/**
 * Pure singleton representation for Minecraft 1.20.5+ unit components (e.g. {@code minecraft:glider}).
 * Presence in a snapshot represents the unit component's presence; absence or removal represents its lack.
 */
public enum Unit {
    INSTANCE;

    @Override
    public String toString() {
        return "unit";
    }
}
