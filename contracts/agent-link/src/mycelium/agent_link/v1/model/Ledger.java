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
     * is in progress or an indeterminate fatal failure holds the id; otherwise null, and the id is running until
     * {@link #finish} or a safe {@link #abandon}.
     */
    Reply enter(String id);

    /**
     * The run of {@code id} is over: store {@code reply} before releasing its running claim, then forget oldest replies
     * beyond the bound. If storing the reply fails, the claim stays held so a retry cannot repeat an uncertain effect.
     */
    void finish(String id, Reply reply);

    /**
     * Releases {@code id} without remembering anything, so it is free to be asked again. Use only when the handler did
     * not start or the caller has established that no effect could have happened; never abandon after a handler error.
     */
    void abandon(String id);

    /** How many replies are remembered right now. */
    int size();
}
