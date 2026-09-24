package mycelium.world_copy.v1.model;

public enum Trait {
    /** Every mutation registers an undo with the transaction; aborting restores prior state. */
    ROLLBACK_PARTICIPATION,
    /** The target can carry block-entity presence on write and clear it on request. */
    BLOCK_ENTITY_CONTROL
}
