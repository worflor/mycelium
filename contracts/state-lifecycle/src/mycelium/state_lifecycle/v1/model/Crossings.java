package mycelium.state_lifecycle.v1.model;

/**
 * The engine: a transition on a host becomes a plan, and a plan is applied inside a transaction. {@link StandardCrossings}
 * is the contract's; the harness runs the laws against broken ones.
 */
public interface Crossings {
    /**
     * The plan for one transition on the given host or hosts. It reads the store and the registry and changes nothing.
     *
     * @throws StaleHostException      the crossing names an incarnation that is not current
     * @throws CrossingException       the crossing does not fit the transition (its shape, its host kind) or the hosts'
     *                                 state (beginning a live host, forking onto a live one)
     */
    <H> Plan<H> plan(TransitionId transition, Crossing<H> crossing, HostStore<H> store, SlotRegistry registry);

    /**
     * Applies a plan, registering the undo of every change in {@code tx} (L8). If any step fails the exception propagates
     * and the caller aborts {@code tx}: nothing of the crossing remains. A plan whose hosts have changed since it was made
     * is refused before anything changes ({@link StaleHostException}, L2).
     */
    <H> void apply(Plan<H> plan, HostStore<H> store, Tx tx);
}
