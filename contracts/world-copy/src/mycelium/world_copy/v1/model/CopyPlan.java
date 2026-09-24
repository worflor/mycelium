package mycelium.world_copy.v1.model;

import java.util.List;

/** The full set of writes and block-entity clears a copy will perform. Planned before any mutation. */
public record CopyPlan(List<Write> writes, List<Pos> sanitize) {
    public CopyPlan {
        writes = List.copyOf(writes);
        sanitize = List.copyOf(sanitize);
    }
}
