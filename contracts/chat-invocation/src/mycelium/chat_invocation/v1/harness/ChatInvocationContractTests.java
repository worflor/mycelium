package mycelium.chat_invocation.v1.harness;

import mycelium.chat_invocation.v1.model.Gate;
import mycelium.chat_invocation.v1.model.Invocation;
import mycelium.chat_invocation.v1.model.Line;
import mycelium.chat_invocation.v1.model.Outcome;
import mycelium.chat_invocation.v1.model.Rules;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** chat.invocation@1: only addressed lines leave, with a bounded, fresh, private, opt-out-respecting snapshot (SPEC.md). */
public final class ChatInvocationContractTests {
    private final Function<Rules, Gate> gates;

    public ChatInvocationContractTests(Function<Rules, Gate> gates) {
        this.gates = gates;
    }

    public Check run() {
        Check c = new Check();
        l1OnlyAddressedLeave(c);
        l1Boundary(c);
        l2Bounded(c);
        l3Fresh(c);
        l4Private(c);
        l5RateBounded(c);
        l6OptOut(c);
        l7NoTruncation(c);
        l8EmptyRejected(c);
        p1Delivered(c);
        p2InOrder(c);
        p3Admitted(c);
        return c;
    }

    private static Invocation committed(Outcome o) {
        return o instanceof Outcome.Committed k ? k.invocation() : null;
    }

    private static List<String> texts(Invocation i) {
        List<String> out = new ArrayList<>();
        if (i != null) {
            for (Line l : i.snapshot()) {
                out.add(l.text());
            }
        }
        return out;
    }

    /** L1: ordinary chat, including lines that mention the trigger elsewhere, passes and carries nothing. */
    private void l1OnlyAddressedLeave(Check c) {
        Gate g = gates.apply(Rules.defaults());
        String[] ordinary = {"hello", "hi @mod", "mail me at x@mod.com", "", "   ", "mod help", "@ mod hi", "@mo d"};
        boolean passed = true;
        long t = 0;
        for (String text : ordinary) {
            Outcome o = g.offer(new Line("alex", text, t += 10));
            passed &= o instanceof Outcome.Passed && !o.addressed();
        }
        c.that(passed, "L1:non-invocation-never-delivered");
    }

    /** L1: a word that begins with the trigger is not the trigger; the boundary characters are. */
    private void l1Boundary(Check c) {
        Gate g = gates.apply(Rules.defaults());
        boolean words = !g.offer(new Line("alex", "@modpack is great", 1)).addressed()
                && !g.offer(new Line("alex", "@moderator pls", 2)).addressed()
                && !g.offer(new Line("alex", "@mods", 3)).addressed();
        c.that(words, "L1:prefix-needs-a-boundary");
        Gate h = gates.apply(Rules.defaults().withBucket(10, 1));
        boolean boundaries = committed(h.offer(new Line("alex", "@mod, hi", 10))) != null
                && committed(h.offer(new Line("alex", "@MOD: hi", 20))) != null
                && committed(h.offer(new Line("alex", "  @Mod\thi", 30))) != null;
        c.that(boundaries, "L1:boundaries-address");
    }

    /** L2: ten lines, a window of three: at most three; a window of zero: none. */
    private void l2Bounded(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(3, 1_000_000));
        for (int i = 0; i < 10; i++) {
            g.offer(new Line("p" + (i % 3), "line " + i, i));
        }
        Invocation inv = committed(g.offer(new Line("alex", "@mod what happened", 100)));
        c.that(inv != null && inv.snapshot().size() <= 3, "L2:snapshot-bounded");
        Gate none = gates.apply(Rules.defaults().withWindow(0, 1_000_000));
        none.offer(new Line("sam", "something", 1));
        Invocation empty = committed(none.offer(new Line("alex", "@mod hi", 2)));
        c.that(empty != null && empty.snapshot().isEmpty(), "L2:snapshot-bounded");
    }

    /** L3: a line older than the window at the time of asking is not in the snapshot. */
    private void l3Fresh(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(10, 1_000));
        g.offer(new Line("sam", "old news", 0));
        g.offer(new Line("sam", "fresh news", 1_500));
        Invocation inv = committed(g.offer(new Line("alex", "@mod summarize", 2_001)));
        c.that(inv != null && texts(inv).equals(Arrays.asList("fresh news")), "L3:snapshot-fresh");
    }

    /** L4: someone else's question, or a rejected one, never shows up in a later snapshot. */
    private void l4Private(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(10, 1_000_000).withBucket(5, 1));
        g.offer(new Line("sam", "@mod how do I claim land", 1));
        g.offer(new Line("sam", "@mod", 2));
        g.offer(new Line("sam", "hello all", 3));
        Invocation inv = committed(g.offer(new Line("alex", "@mod what did sam say", 4)));
        boolean clean = inv != null;
        for (String t : texts(inv)) {
            clean &= !t.toLowerCase().contains("@mod");
        }
        c.that(clean && texts(inv).equals(Arrays.asList("hello all")), "L4:invocations-private");
    }

    /** L5: a seeded flood from one principal: never more than burst + span/refill admitted over any span. */
    private void l5RateBounded(Check c) {
        Rules rules = Rules.defaults().withBucket(3, 1_000);
        Gate g = gates.apply(rules);
        Seeded r = new Seeded(0xC4A7L);
        List<Long> admitted = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 3000; i++) {
            t += r.nextInt(400);
            if (committed(g.offer(new Line("spam", "@mod again " + i, t))) != null) {
                admitted.add(t);
            }
        }
        boolean bounded = true;
        for (int i = 0; i < admitted.size() && bounded; i++) {
            for (int j = i; j < admitted.size(); j++) {
                long span = admitted.get(j) - admitted.get(i);
                if (j - i + 1 > rules.burst() + span / rules.refillMs()) {
                    bounded = false;
                    break;
                }
            }
        }
        c.that(bounded && !admitted.isEmpty(), "L5:rate-bounded");
    }

    /** L6: opting out removes earlier lines and keeps later ones out; opting back in restores nothing old. */
    private void l6OptOut(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(20, 1_000_000).withBucket(10, 1));
        g.offer(new Line("shy", "before", 1));
        g.offer(new Line("sam", "sam talks", 2));
        g.optOut("shy", true);
        g.offer(new Line("shy", "during", 3));
        Invocation a = committed(g.offer(new Line("alex", "@mod recap", 4)));
        g.optOut("shy", false);
        g.offer(new Line("shy", "after", 5));
        Invocation b = committed(g.offer(new Line("alex", "@mod recap again", 6)));
        boolean ok = a != null && b != null
                && texts(a).equals(Arrays.asList("sam talks"))
                && texts(b).equals(Arrays.asList("sam talks", "after"));
        c.that(ok, "L6:opt-out-honoured");
    }

    /** L7: too long is rejected, not shortened. */
    private void l7NoTruncation(Check c) {
        Gate g = gates.apply(Rules.defaults().withMaxQuery(10));
        Outcome o = g.offer(new Line("alex", "@mod this question is far too long", 1));
        c.that(o instanceof Outcome.Rejected r && r.reason() == Outcome.Reason.TOO_LONG, "L7:no-silent-truncation");
        Invocation fits = committed(g.offer(new Line("alex", "@mod 0123456789", 2)));
        c.that(fits != null && fits.query().equals("0123456789"), "L7:exactly-max-fits");
    }

    /** L8: a bare trigger is a rejection, and it is still the asker's (addressed, not broadcast). */
    private void l8EmptyRejected(Check c) {
        Gate g = gates.apply(Rules.defaults());
        Outcome o = g.offer(new Line("alex", "  @mod   ", 1));
        c.that(o instanceof Outcome.Rejected r && r.reason() == Outcome.Reason.EMPTY_QUERY && o.addressed(),
                "L8:empty-is-rejected");
    }

    /** P1: an addressed line under budget is committed with exactly its query. */
    private void p1Delivered(Check c) {
        Gate g = gates.apply(Rules.defaults());
        Invocation inv = committed(g.offer(new Line("alex", "@mod   what is the tps?  ", 5)));
        c.that(inv != null && inv.query().equals("what is the tps?") && inv.principal().equals("alex") && inv.at() == 5,
                "P1:invocation-delivered");
    }

    /** P2: the snapshot is the most recent eligible lines, oldest first, including the asker's own. */
    private void p2InOrder(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(3, 1_000_000));
        g.offer(new Line("sam", "one", 1));
        g.offer(new Line("alex", "two", 2));
        g.offer(new Line("sam", "three", 3));
        g.offer(new Line("kim", "four", 4));
        Invocation inv = committed(g.offer(new Line("alex", "@mod context?", 5)));
        c.that(texts(inv).equals(Arrays.asList("two", "three", "four")), "P2:snapshot-in-order");
    }

    /** P3: a principal under budget is admitted, spends the burst, is refused, and is admitted after refilling. */
    private void p3Admitted(Check c) {
        Rules rules = Rules.defaults().withBucket(2, 1_000);
        Gate g = gates.apply(rules);
        boolean first = committed(g.offer(new Line("alex", "@mod a", 0))) != null
                && committed(g.offer(new Line("alex", "@mod b", 1))) != null;
        Outcome third = g.offer(new Line("alex", "@mod c", 2));
        boolean limited = third instanceof Outcome.Rejected r && r.reason() == Outcome.Reason.RATE_LIMITED;
        boolean others = committed(g.offer(new Line("kim", "@mod mine", 3))) != null;
        boolean again = committed(g.offer(new Line("alex", "@mod d", 2 + 2 * rules.refillMs()))) != null;
        c.that(first && others && again, "P3:under-budget-admitted");
        c.that(limited, "L5:rate-bounded");
    }
}
