package mycelium.data_generation.v1.model;

import mycelium.data_catalog.v1.model.Snapshot;

import java.util.Map;
import java.util.Optional;

/**
 * The live generation and the way to replace it (data.generation@1). The laws (SPEC.md §3): whole or nothing, references
 * resolve, one name by a fixed canonical hash, numbers rise, a held generation never changes, every finding at once,
 * every entry accounted for, and an offer never throws.
 */
public interface Generations<G> {
    /** The live generation, or empty before the first publication. Never a part of one, never a mix of two. */
    Optional<Generation<G>> current();

    /** Offers one snapshot per kind, by kind id: publishes the candidate whole, or keeps the live generation entire. */
    Outcome<G> offer(Map<String, ? extends Snapshot<?>> offered);
}
