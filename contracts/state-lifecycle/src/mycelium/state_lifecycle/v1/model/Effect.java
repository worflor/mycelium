package mycelium.state_lifecycle.v1.model;

/** One step of a plan. Effects are data: a plan is read, compared and ordered before anything is applied. */
public sealed interface Effect permits Effect.OfHost, Effect.OfSlot {
    /** A host becomes live ({@code BEGIN}, with its object) or its incarnation ends ({@code END}). */
    record OfHost(Kind kind, HostRef ref, Object handle) implements Effect {
        public enum Kind { BEGIN, END }
    }

    /**
     * What one slot does at the crossing. {@code from} is where the value is read, {@code to} where it is written; they
     * are the same incarnation except for CARRY, COPY, MOVE and a fork's MIGRATE. Operations that act on one place
     * (DROP, RESET, RELEASE, LOAD, PERSIST, REGENERATE) act on {@code to}.
     */
    record OfSlot(Slot<?> slot, Op op, HostRef from, HostRef to) implements Effect {
        public enum Op {
            /** Nothing changes. Present in a plan so that it covers every slot. */
            KEEP,
            /** The value ceases to exist: live, and for a durable slot stored. */
            DROP,
            /** The live value is released and storage keeps its form (a durable value at an end). */
            RELEASE,
            /** The live value becomes the slot's initial value. */
            RESET,
            /** The value at {@code to} becomes the policy's function of the value at {@code from}, if there is one. */
            MIGRATE,
            /** The stored form is decoded into the live value, if there is one. */
            LOAD,
            /** The live value is encoded and stored; an absent one removes the stored form. */
            PERSIST,
            /** The value moves from one incarnation of a host to the next. */
            CARRY,
            /** The value at {@code from} is also given to {@code to}. */
            COPY,
            /** {@code to} gets the policy's function of its own host id. */
            REGENERATE,
            /** The value, live and stored, moves from one host to the other. */
            MOVE
        }

        public static OfSlot on(Slot<?> slot, Op op, HostRef ref) {
            return new OfSlot(slot, op, ref, ref);
        }
    }
}
