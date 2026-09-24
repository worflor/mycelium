package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.PlayerId;
import mycelium.player_state.v1.model.StateBlob;
import mycelium.player_state.v1.model.Tx;

import java.util.LinkedHashMap;
import java.util.Map;

/** Drops one field on every write: violates L1 round-trip equality. */
public final class LossyStatePort extends MemoryStatePort {
    @Override
    public void write(PlayerId id, StateBlob blob, Tx tx) {
        Map<String, String> fields = new LinkedHashMap<>(blob.fields());
        if (!fields.isEmpty()) {
            String firstKey = fields.keySet().iterator().next();
            fields.remove(firstKey);
        }
        super.write(id, new StateBlob(blob.schema(), fields), tx);
    }
}
