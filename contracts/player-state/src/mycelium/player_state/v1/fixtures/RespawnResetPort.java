package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.PlayerId;

/** Clears the player's data on death/respawn: violates L2/P1. */
public final class RespawnResetPort extends MemoryStatePort {
    @Override
    public void simulateDeathRespawn(PlayerId id) {
        store.remove(id);
    }
}
