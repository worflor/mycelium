package mycelium.chat_invocation.v1.model;

/** What chat.invocation@1 governs (SPEC.md): every chat line passes through {@link #offer}, in order. */
public interface Gate {
    Outcome offer(Line line);

    /** A principal leaves (or rejoins) snapshots. Leaving drops what is held of them; rejoining restores nothing. */
    void optOut(String principal, boolean out);

    /** Whether a principal is currently out of snapshots. */
    boolean optedOut(String principal);

    /** What {@link #readyInMs} answers when a gate cannot say (the default): not a wait, not zero, just unknown. */
    long UNKNOWN = -1L;

    /**
     * How long, from {@code now}, until this principal's next addressed line would be admitted rather than rejected
     * {@code RATE_LIMITED}: 0 when it would be admitted now (under budget, or never seen), otherwise the exact whole
     * number of milliseconds, so an addressed line at {@code now + readyInMs} is admitted and one a millisecond
     * earlier is not (law P4). {@code now} is clamped like an offered line's time: never before the gate's last time.
     * It only asks: nothing is spent, held or moved by the question.
     *
     * <p>A gate that cannot say returns {@link #UNKNOWN}, which is what this default does. It is not 0, because 0
     * claims the principal may ask now. A gate that answers anything else must keep P4.
     */
    default long readyInMs(String principal, long now) {
        return UNKNOWN;
    }
}
