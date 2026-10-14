package mycelium.data_generation.v1.model;

/**
 * What a broken entry of a kind does to the candidate (SPEC.md §2). A broken entry is one the catalog rejected, one an
 * ERROR finding names, or one whose reference names nothing published.
 */
public enum Policy {
    /** The candidate is refused, and the live generation keeps serving. */
    REQUIRED,
    /** The entry is left out of the generation and reported, with whatever referred to it, transitively. */
    OPTIONAL
}
