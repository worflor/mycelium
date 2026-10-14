package mycelium.data_generation.v1.model;

import mycelium.data_catalog.v1.model.Snapshot;

import java.util.List;
import java.util.Map;

/**
 * The mod's half of a generation (SPEC.md §2): which kinds take part, which version of the code interprets them, how an
 * offer is read, and how a resolved candidate is built. It judges content; the contract judges references, accounting,
 * hashing and publication.
 *
 * @param <D> the assembler's own reading of one offer, handed from {@link #read} to {@link #build}
 * @param <G> the generation's value
 */
public interface Assembler<D, G> {
    /** The version of the code that gives the data its meaning. Part of the hash: raise it when a record's meaning changes. */
    String interpreter();

    /** The kinds that take part, each id once. */
    List<Kind> kinds();

    /**
     * Reads every offered snapshot (one per kind, by kind id): a part per entry, with its canonical content and its
     * references, and a finding for each problem. Cross-catalog reading (a structure read against the ruleset) belongs
     * here; a judgement about one entry is a finding with that entry as subject.
     */
    Draft<D> read(Map<String, Snapshot<?>> offered);

    /**
     * Builds the value from the candidate's surviving entries, {@code resolved.manifest()}. The whole-candidate checks
     * live here; a refusal, or an ERROR finding, refuses the candidate.
     */
    Assembly<G> build(D draft, Resolved resolved);
}
