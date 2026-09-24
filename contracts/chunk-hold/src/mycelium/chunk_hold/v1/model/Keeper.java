package mycelium.chunk_hold.v1.model;

/**
 * What chunk.hold@1 governs (SPEC.md): something that keeps places loaded for several holders over an engine that does
 * not count them ({@link Tickets}). The laws are about the engine's tickets after each call.
 */
public interface Keeper<K, O> {
    /** {@code owner} holds {@code place} until it lets go. */
    void hold(K place, O owner);

    /** {@code owner} lets go of {@code place}. */
    void release(K place, O owner);

    /** {@code owner} lets go of everything it holds. */
    void releaseAll(O owner);

    /** Held until {@code until} unless asked for again; nobody has to let go of it. */
    void lease(K place, long until);

    /** Time is {@code now}: leases whose time has come lapse. */
    void expire(long now);

    /** Every held place's ticket, taken again (its timeout starts over). */
    void renew();
}
