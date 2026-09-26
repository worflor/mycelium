package mycelium.chat_invocation.v1.model;

/**
 * What the gate did with one line (SPEC.md). {@link Passed} carries nothing: that is law L1. {@link Committed} is the
 * only way anything leaves. {@link Rejected} is an addressed line that is not delivered, with the reason to tell the
 * asker.
 */
public sealed interface Outcome permits Outcome.Passed, Outcome.Committed, Outcome.Rejected {
    /** Why an addressed line was not delivered. */
    enum Reason { EMPTY_QUERY, TOO_LONG, RATE_LIMITED }

    /** Not addressed. Broadcast it as usual; nothing else happens. */
    final class Passed implements Outcome {
        public static final Passed INSTANCE = new Passed();

        private Passed() {
        }

        @Override
        public String toString() {
            return "Passed";
        }
    }

    record Committed(Invocation invocation) implements Outcome {
    }

    record Rejected(String principal, Reason reason) implements Outcome {
    }

    /** True for an addressed line, delivered or not: such a line is the asker's and is not broadcast. */
    default boolean addressed() {
        return !(this instanceof Passed);
    }
}
