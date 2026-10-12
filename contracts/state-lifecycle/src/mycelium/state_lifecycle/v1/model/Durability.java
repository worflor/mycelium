package mycelium.state_lifecycle.v1.model;

/** Where a slot's value lives. */
public enum Durability {
    /** In memory, for as long as its host's current incarnation lives. */
    SESSION,
    /** Saved with the host through the storage port, encoded by the slot's {@link SlotCodec}. */
    DURABLE
}
