package mycelium.chat_invocation.v1.model;

/** What chat.invocation@1 governs (SPEC.md): every chat line passes through {@link #offer}, in order. */
public interface Gate {
    Outcome offer(Line line);

    /** A principal leaves (or rejoins) snapshots. Leaving drops what is held of them; rejoining restores nothing. */
    void optOut(String principal, boolean out);

    /** Whether a principal is currently out of snapshots. */
    boolean optedOut(String principal);

    /**
     * The assistant's settings arrived or changed (law P7): from the next line the gate judges by {@code rules}, and no
     * one who opted out comes back into a snapshot. What else it holds (lines, rate limits) the gate may keep within the
     * new bounds or start afresh. A gate never waits for this: until it is called, and from its first line, it judges by
     * the rules it was built with, the defaults or the last it was given (law P6), so an assistant that is absent, has
     * crashed or has not yet said what its settings are never holds, cancels or delays an ordinary line.
     *
     * <p>The default ignores the settings, which is what a gate that predates this method does: it keeps the rules it was
     * built with and fails P7, and keeps every other law.
     */
    default void settings(Rules rules) {
    }

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
