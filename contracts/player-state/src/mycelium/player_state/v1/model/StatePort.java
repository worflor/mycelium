package mycelium.player_state.v1.model;

import java.util.Optional;
import java.util.Set;

public interface StatePort {
    Optional<StateBlob> read(PlayerId id);

    void write(PlayerId id, StateBlob blob, Tx tx);

    Set<Trait> traits();
}
