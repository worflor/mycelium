package mycelium.state_lifecycle.v1.model;

import java.util.Comparator;

/**
 * One thing wrong in a declaration, naming the slot and, where there is one, the transition. A message is for a reader;
 * the code and the names are for a program.
 */
public record Diagnostic(Code code, SlotId slot, TransitionId transition, String message) implements Comparable<Diagnostic> {
    public enum Code {
        /** A slot declares no policy for a transition its scope admits (L1). */
        MISSING_POLICY,
        /** A slot declares a policy for a transition that is not declared. */
        UNKNOWN_TRANSITION,
        /** A slot declares a policy for a transition that does not apply to its scope. */
        OUT_OF_SCOPE_POLICY,
        /** A policy that means nothing on that transition's shape (COPY, REGENERATE or EXCLUSIVE away from a fork, a session value kept past an end). */
        INADMISSIBLE_POLICY,
        /** An EXCLUSIVE slot that declares a policy which would put its value on a second host (L3). */
        EXCLUSIVE_CREATES_VALUE,
        /** A durable slot with no codec. */
        DURABLE_WITHOUT_CODEC,
        /** Two slots with one id. */
        DUPLICATE_SLOT,
        /** A dependency on a slot that is not in the registry. */
        UNKNOWN_DEPENDENCY,
        /** A dependency on a slot of another scope: a plan orders one host's slots. */
        CROSS_SCOPE_DEPENDENCY,
        /** Slots whose dependencies lead back to themselves. */
        DEPENDENCY_CYCLE
    }

    private static final Comparator<Diagnostic> ORDER = Comparator
            .comparing(Diagnostic::code)
            .thenComparing(d -> d.slot == null ? "" : d.slot.value())
            .thenComparing(d -> d.transition == null ? "" : d.transition.value())
            .thenComparing(Diagnostic::message);

    @Override
    public int compareTo(Diagnostic other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return code + ": " + message;
    }
}
