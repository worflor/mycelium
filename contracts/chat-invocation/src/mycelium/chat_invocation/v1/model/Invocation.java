package mycelium.chat_invocation.v1.model;

import java.util.List;

/** An addressed line that leaves the gate: the asker, the query, when, and the bounded snapshot before it. */
public record Invocation(String principal, String query, long at, List<Line> snapshot) {
}
