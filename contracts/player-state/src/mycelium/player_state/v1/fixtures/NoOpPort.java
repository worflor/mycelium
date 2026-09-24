package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.Tx;

/** Write is ignored entirely: honest inspection, dishonest mutation. Violates L1/P1, caught by the same
 * positive-obligation labels a NoOp must fail, per CONTRACT-PACKAGE rule 3. */
public final class NoOpPort extends MemoryStatePort {
    @Override
    public void write(PlayerId id, StateBlob blob, Tx tx) {
        // intentionally does nothing
    }
}
