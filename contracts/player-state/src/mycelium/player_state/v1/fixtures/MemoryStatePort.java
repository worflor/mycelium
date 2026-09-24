package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.StatePort;
import mycelium.player_state.v1.model.Trait;
import mycelium.player_state.v1.model.Tx;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reference in-memory port. DURABLE and RESPAWN_SAFE both hold: every lifecycle simulation is a no-op on
 * the backing store, because a correct durable backend keeps player data through all of them.
 */
public class MemoryStatePort implements StatePort {
    protected final Map<PlayerId, StateBlob> store = new HashMap<>();

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.DURABLE, Trait.RESPAWN_SAFE);
    }

    @Override
    public Optional<StateBlob> read(PlayerId id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void write(PlayerId id, StateBlob blob, Tx tx) {
        StateBlob previous = store.get(id);
        boolean hadPrevious = store.containsKey(id);
        store.put(id, blob);
        tx.registerUndo(() -> {
            if (hadPrevious) {
                store.put(id, previous);
            } else {
                store.remove(id);
            }
        });
    }

    public void simulateDeathRespawn(PlayerId id) {
        // reference keeps data through death/respawn
    }

    public void simulateDimensionChange(PlayerId id) {
        // reference keeps data through dimension change
    }

    public void simulateRelog(PlayerId id) {
        // reference keeps data through relog
    }

    public void simulateRestart() {
        // reference keeps data through a full restart
    }
}
