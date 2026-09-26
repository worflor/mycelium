package mycelium.chat_invocation.v1.model;

/** What chat.invocation@1 governs (SPEC.md): every chat line passes through {@link #offer}, in order. */
public interface Gate {
    Outcome offer(Line line);

    /** A principal leaves (or rejoins) snapshots. Leaving drops what is held of them; rejoining restores nothing. */
    void optOut(String principal, boolean out);

    /** Whether a principal is currently out of snapshots. */
    boolean optedOut(String principal);
}
