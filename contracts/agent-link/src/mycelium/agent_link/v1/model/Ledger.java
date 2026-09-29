package mycelium.agent_link.v1.model;

/**
 * What a session remembers about mutating requests, kept apart from the session: which ids have an answer, and which
 * are running right now (SPEC.md, L8). A consumer whose own code can be replaced while the server runs (a mod that
 * hot-swaps its payload) keeps one ledger outside what is replaced and builds each new session over it, so a request
 * in flight across the replacement is neither run twice nor treated as new.
 *
 * <p>Every method is atomic with respect to the others, and to any other session over the same ledger: two sessions
 * asking {@link #enter} for one id never both get to run it.
 */
public interface Ledger {
    /**
     * Claims {@code id} for a run. Returns the remembered reply when the id has one; a {@code Refused(BUSY)} when a run
     * of it is in progress; otherwise null, and the id is running until {@link #finish} or {@link #abandon}.
     */
    Reply enter(String id);

    /** The run of {@code id} is over: remember {@code reply} for its retries (the oldest are forgotten beyond the bound). */
    void finish(String id, Reply reply);

    /** The run of {@code id} is over and nothing is remembered: the id is free to be asked again. */
    void abandon(String id);

    /** How many replies are remembered right now. */
    int size();
}
