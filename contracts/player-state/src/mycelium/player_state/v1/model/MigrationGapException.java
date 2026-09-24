package mycelium.player_state.v1.model;

/** Thrown by {@link MigrationChain#upgrade} when the actual walk from schema to schema finds a missing step,
 * regardless of what {@link MigrationChain#total} claims. Executors must catch this rather than trust the
 * declaration (basis note mirrors DESIGN's trait-declaration-is-a-claim discipline). */
public final class MigrationGapException extends RuntimeException {
    public MigrationGapException(int stuckAtSchema, int targetSchema) {
        super("no migration registered from schema " + stuckAtSchema + " while walking to " + targetSchema);
    }
}
