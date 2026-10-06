package mycelium.chat_invocation.v1.model;

import java.util.List;

/**
 * An addressed line that leaves the gate: the asker, the query, when, the bounded snapshot before it, and the trigger
 * the line began with (spelled as the rules spell it; law P5). {@code trigger} is {@code null} only from a gate that
 * does not name it.
 */
public record Invocation(String principal, String query, long at, List<Line> snapshot, String trigger) {
    /** An invocation from a gate that does not name the trigger. */
    public Invocation(String principal, String query, long at, List<Line> snapshot) {
        this(principal, query, at, snapshot, null);
    }
}
