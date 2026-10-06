package mycelium.chat_invocation.v1.fixtures;

import mycelium.chat_invocation.v1.model.Gate;
import mycelium.chat_invocation.v1.model.Invocation;
import mycelium.chat_invocation.v1.model.Line;
import mycelium.chat_invocation.v1.model.Outcome;
import mycelium.chat_invocation.v1.model.Rules;
import mycelium.chat_invocation.v1.model.Triggers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An independent gate with one knob per way to get the contract wrong (SPEC.md, rejection matrix). With every knob off
 * it is a second correct implementation; each broken fixture turns exactly one knob on.
 */
public final class FlexGate implements Gate {
    /** The ways to be wrong. */
    public enum Flaw { NONE, NO_OP, LEAKY, EAGER, UNBOUNDED, STALE, ECHO, GREEDY, DEAF, TRUNCATING, SHUFFLED, STINGY, MUTE_WAIT, EARLY_WAIT, NAMELESS }

    private final Rules rules;
    private final Flaw flaw;
    private final List<Line> held = new ArrayList<>();
    private final Set<String> out = new HashSet<>();
    private final Map<String, List<Long>> admitted = new HashMap<>();

    public FlexGate(Rules rules, Flaw flaw) {
        this.rules = rules;
        this.flaw = flaw;
    }

    @Override
    public Outcome offer(Line line) {
        Triggers.Match match = flaw == Flaw.EAGER ? eagerMatch(line.text()) : Triggers.match(rules, line.text());
        String query = match == null ? null : match.query();
        // a nameless gate delivers everything else right and never says which trigger addressed the line
        String trigger = match == null || flaw == Flaw.NAMELESS ? null : match.trigger();
        if (flaw == Flaw.NO_OP) {
            return Outcome.Passed.INSTANCE;
        }
        if (query == null) {
            hold(line);
            if (flaw == Flaw.LEAKY) {
                return new Outcome.Committed(new Invocation(line.principal(), line.text(), line.at(), snapshot(line.at())));
            }
            return Outcome.Passed.INSTANCE;
        }
        if (flaw == Flaw.ECHO) {
            hold(line);
        }
        if (query.isEmpty()) {
            return new Outcome.Rejected(line.principal(), Outcome.Reason.EMPTY_QUERY, trigger);
        }
        if (query.length() > rules.maxQueryChars()) {
            if (flaw != Flaw.TRUNCATING) {
                return new Outcome.Rejected(line.principal(), Outcome.Reason.TOO_LONG, trigger);
            }
            query = query.substring(0, rules.maxQueryChars());
        }
        if (flaw != Flaw.GREEDY && !admit(line.principal(), line.at())) {
            return new Outcome.Rejected(line.principal(), Outcome.Reason.RATE_LIMITED, trigger);
        }
        List<Line> snap = snapshot(line.at());
        if (flaw == Flaw.SHUFFLED) {
            Collections.reverse(snap);
        }
        return new Outcome.Committed(new Invocation(line.principal(), query, line.at(), snap, trigger));
    }

    @Override
    public void optOut(String principal, boolean optOut) {
        if (flaw == Flaw.DEAF) {
            return;
        }
        if (optOut) {
            out.add(principal);
            held.removeIf(l -> l.principal().equals(principal));
        } else {
            out.remove(principal);
        }
    }

    @Override
    public boolean optedOut(String principal) {
        return out.contains(principal);
    }

    /**
     * The sliding log's exact wait: none while fewer than {@code burst} admissions fall inside the last
     * {@code burst * refillMs}, otherwise until the oldest of them leaves it. MUTE_WAIT always says 0 (it never
     * tells anyone to wait); EARLY_WAIT says one millisecond too little.
     */
    @Override
    public long readyInMs(String principal, long now) {
        if (flaw == Flaw.MUTE_WAIT) {
            return 0;
        }
        List<Long> log = admitted.get(principal);
        if (log == null) {
            return 0;
        }
        long span = (long) rules.burst() * rules.refillMs();
        long oldest = Long.MAX_VALUE;
        int inside = 0;
        for (long t : log) {
            if (flaw == Flaw.STINGY || t > now - span) {
                inside++;
                oldest = Math.min(oldest, t);
            }
        }
        if (inside < rules.burst()) {
            return 0;
        }
        long wait = oldest + span - now;
        return flaw == Flaw.EARLY_WAIT ? wait - 1 : wait;
    }

    private Triggers.Match eagerMatch(String text) {
        String t = text.trim();
        for (String p : rules.prefixes()) {
            if (t.regionMatches(true, 0, p, 0, p.length())) {
                return new Triggers.Match(p, t.substring(p.length()).trim());
            }
        }
        return null;
    }

    private void hold(Line line) {
        if (out.contains(line.principal()) || (rules.windowLines() == 0 && flaw != Flaw.UNBOUNDED)) {
            return;
        }
        held.add(line);
        if (flaw != Flaw.UNBOUNDED) {
            while (held.size() > rules.windowLines()) {
                held.remove(0);
            }
        }
    }

    private List<Line> snapshot(long now) {
        List<Line> lines = new ArrayList<>();
        for (Line l : held) {
            if (flaw == Flaw.STALE || (l.at() >= now - rules.windowMs() && l.at() <= now)) {
                lines.add(l);
            }
        }
        return lines;
    }

    /**
     * A sliding log: admitted while fewer than {@code burst} admissions fall inside the last {@code burst * refillMs}.
     * It admits no more than the reference's bucket over any span, so it satisfies L5 and P3 independently. STINGY
     * never forgets an admission, so it never refills.
     */
    private boolean admit(String principal, long now) {
        List<Long> log = admitted.computeIfAbsent(principal, p -> new ArrayList<>());
        if (flaw != Flaw.STINGY) {
            long horizon = now - (long) rules.burst() * rules.refillMs();
            log.removeIf(t -> t <= horizon);
        }
        if (log.size() >= rules.burst()) {
            return false;
        }
        log.add(now);
        return true;
    }
}
