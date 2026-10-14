package mycelium.data_generation.v1.model;

import java.util.Objects;

/**
 * A catalog that takes part in a generation (a {@code data.catalog@1} snapshot is offered under this id), and what a
 * broken entry of it does to the candidate.
 */
public record Kind(String id, Policy policy) {
    public Kind {
        Ref.wellFormed(id, "kind id");
        Objects.requireNonNull(policy, "policy");
    }

    public static Kind required(String id) {
        return new Kind(id, Policy.REQUIRED);
    }

    public static Kind optional(String id) {
        return new Kind(id, Policy.OPTIONAL);
    }
}
