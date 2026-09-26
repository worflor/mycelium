package mycelium.chat_invocation.v1.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The reference gate. A bounded window of unaddressed lines from principals who have not opted out, a token bucket per
 * principal, and nothing else: an addressed line is never held, and an unaddressed one leaves only inside a snapshot.
 * Not thread-safe: offer every line from one thread (the server thread).
 */
public final class ChatGate implements Gate {
    private final Rules rules;
    private final ArrayDeque<Line> window = new ArrayDeque<>();
    private final Set<String> out = new HashSet<>();
    private final Map<String, long[]> buckets = new HashMap<>(); // principal -> {tokens, lastRefill}
    private long last = Long.MIN_VALUE;

    public ChatGate(Rules rules) {
        this.rules = rules;
    }

    public Rules rules() {
        return rules;
    }

    @Override
    public Outcome offer(Line line) {
        long now = Math.max(line.at(), last);
        last = now;
        String query = Triggers.query(rules, line.text());
        if (query == null) {
            remember(new Line(line.principal(), line.text(), now));
            return Outcome.Passed.INSTANCE;
        }
        if (query.isEmpty()) {
            return new Outcome.Rejected(line.principal(), Outcome.Reason.EMPTY_QUERY);
        }
        if (query.length() > rules.maxQueryChars()) {
            return new Outcome.Rejected(line.principal(), Outcome.Reason.TOO_LONG);
        }
        if (!spend(line.principal(), now)) {
            return new Outcome.Rejected(line.principal(), Outcome.Reason.RATE_LIMITED);
        }
        return new Outcome.Committed(new Invocation(line.principal(), query, now, snapshot(now)));
    }

    @Override
    public void optOut(String principal, boolean optOut) {
        if (optOut) {
            out.add(principal);
            window.removeIf(l -> l.principal().equals(principal));
        } else {
            out.remove(principal);
        }
    }

    @Override
    public boolean optedOut(String principal) {
        return out.contains(principal);
    }

    /** Forget every held line (a server stopping). */
    public void clearWindow() {
        window.clear();
    }

    private void remember(Line line) {
        if (rules.windowLines() == 0 || out.contains(line.principal())) {
            return;
        }
        window.addLast(line);
        while (window.size() > rules.windowLines()) {
            window.removeFirst();
        }
    }

    private List<Line> snapshot(long now) {
        List<Line> lines = new ArrayList<>();
        long oldest = now - rules.windowMs();
        for (Line l : window) {
            if (l.at() >= oldest && l.at() <= now) {
                lines.add(l);
            }
        }
        return Collections.unmodifiableList(lines);
    }

    private boolean spend(String principal, long now) {
        long[] b = buckets.get(principal);
        if (b == null) {
            b = new long[]{rules.burst(), now};
            buckets.put(principal, b);
        }
        long refills = (now - b[1]) / rules.refillMs();
        if (refills > 0) {
            b[0] = Math.min(rules.burst(), b[0] + refills);
            b[1] = b[0] == rules.burst() ? now : b[1] + refills * rules.refillMs();
        }
        if (b[0] <= 0) {
            return false;
        }
        b[0]--;
        return true;
    }
}
