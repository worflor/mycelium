package mycelium.dimension_travel.v2.model;

/**
 * The consumer's own ledger, asked once to settle a trip the process didn't live to finish: what the traveller left
 * with ({@code journal.carried()}) and what they have now. What stays and what goes is the consumer's rule; the
 * contract only promises it is asked exactly once, after the traveller is home, and only after the outer commit.
 */
@FunctionalInterface
public interface Settle {
    void settle(Journal journal, Inventory now);
}
