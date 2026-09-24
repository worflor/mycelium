package mycelium.player_state.v1.fixtures;

/** Clears every player's data on restart: violates L2/P1. */
public final class RestartAmnesiaPort extends MemoryStatePort {
    @Override
    public void simulateRestart() {
        store.clear();
    }
}
