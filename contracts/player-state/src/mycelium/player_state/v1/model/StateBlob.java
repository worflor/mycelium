package mycelium.player_state.v1.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable snapshot of a player's stored state at a schema version. */
public record StateBlob(int schema, Map<String, String> fields) {
    public StateBlob {
        Objects.requireNonNull(fields, "fields");
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }
}
