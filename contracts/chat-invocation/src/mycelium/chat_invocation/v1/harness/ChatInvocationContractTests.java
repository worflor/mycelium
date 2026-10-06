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
import java.util.function.Supplier;

/** chat.invocation@1: only addressed lines leave, with a bounded, fresh, private, opt-out-respecting snapshot (SPEC.md). */
public final class ChatInvocationContractTests {
    private final Function<Rules, Gate> gates;
    private final Supplier<Gate> unconfigured;

    /** {@code gates} builds a gate with the rules given; a gate built with {@link Rules#defaults()} is also the one that has been told nothing. */
    public ChatInvocationContractTests(Function<Rules, Gate> gates) {
        this(gates, () -> gates.apply(Rules.defaults()));
    }

    /** {@code unconfigured} builds a gate that has been told nothing: no rules, no settings from the assistant (law P6). */
    public ChatInvocationContractTests(Function<Rules, Gate> gates, Supplier<Gate> unconfigured) {
        this.gates = gates;
        this.unconfigured = unconfigured;
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
        p4ReadyInIsExact(c);
        p5TriggerIsNamed(c);
        p6AdmitsFromTheFirstLine(c);
        p7SettingsApplyAtOnce(c);
        p7SettingsKeepOptOuts(c);
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

    /**
     * P4: over seeded traffic from two principals under several bucket rules, {@code readyInMs} is never unknown or
     * negative, asking twice changes nothing, 0 means the next addressed line is admitted, and a positive wait is
     * exact: still limited a millisecond before it, admitted at it. A principal never seen is ready at once.
     */
    private void p4ReadyInIsExact(Check c) {
        long[][] shapes = {{1, 1}, {1, 7}, {2, 1_000}, {3, 10_000}, {4, 13}};
        boolean known = true;
        boolean pure = true;
        boolean zeroAdmits = true;
        boolean exact = true;
        boolean unseen = true;
        int waits = 0;
        int admits = 0;
        for (int s = 0; s < shapes.length; s++) {
            int burst = (int) shapes[s][0];
            long refill = shapes[s][1];
            Gate g = gates.apply(Rules.defaults().withBucket(burst, refill));
            unseen &= g.readyInMs("nobody", 0) == 0;
            Seeded r = new Seeded(0xA11EL + s);
            String[] who = {"spam", "kim"};
            long t = 0;
            for (int i = 0; i < 200; i++) {
                t += r.nextInt(0, (int) Math.min(2 * refill + 1, 100_000));
                String p = who[r.nextInt(2)];
                long w = g.readyInMs(p, t);
                known &= w >= 0;
                pure &= w == g.readyInMs(p, t);
                if (w < 0) {
                    continue;
                }
                if (w == 0) {
                    zeroAdmits &= committed(g.offer(new Line(p, "@mod q" + i, t))) != null;
                    admits++;
                    continue;
                }
                waits++;
                exact &= limited(g.offer(new Line(p, "@mod q" + i, t)));
                exact &= limited(g.offer(new Line(p, "@mod q" + i, t + w - 1)));
                exact &= g.readyInMs(p, t + w - 1) == 1;
                exact &= committed(g.offer(new Line(p, "@mod q" + i, t + w))) != null;
                t += w;
                unseen &= g.readyInMs("nobody", t) == 0;
            }
        }
        c.that(known && pure && zeroAdmits && exact && unseen && waits > 0 && admits > 0, "P4:ready-in-is-exact");
    }

    /**
     * P5: an addressed line, delivered or rejected for any reason, names the trigger it began with, as the rules spell
     * it (not as it was typed), and the first listed trigger that addresses it wins. A line that is not addressed names
     * nothing: it carries nothing at all (L1).
     */
    private void p5TriggerIsNamed(Check c) {
        Rules rules = Rules.defaults().withPrefixes(Arrays.asList("@mod", "@Recon", "@modpack"));
        Gate g = gates.apply(rules.withBucket(1, 1_000_000));
        Invocation typedApart = committed(g.offer(new Line("alex", "@RECON what is the tps?", 1)));
        Outcome limited = g.offer(new Line("alex", "@mod again", 2));
        Gate h = gates.apply(rules.withBucket(10, 1));
        Invocation comma = committed(h.offer(new Line("alex", "  @mod, hi", 1)));
        // "@modpack" begins with "@mod" but is not it (no boundary), so only the third trigger addresses the line
        Invocation longer = committed(h.offer(new Line("alex", "@modpack: hi", 2)));
        Outcome bare = h.offer(new Line("alex", "@recon", 3));
        Outcome tooLong = gates.apply(rules.withMaxQuery(10)).offer(new Line("alex", "@mod this question is far too long", 1));
        boolean named = typedApart != null && "@Recon".equals(typedApart.trigger())
                && comma != null && "@mod".equals(comma.trigger())
                && longer != null && "@modpack".equals(longer.trigger())
                && rejectedBy(bare, Outcome.Reason.EMPTY_QUERY, "@Recon")
                && rejectedBy(tooLong, Outcome.Reason.TOO_LONG, "@mod")
                && rejectedBy(limited, Outcome.Reason.RATE_LIMITED, "@mod");
        c.that(named, "P5:trigger-is-named");
    }

    /**
     * P6: a gate that has been told nothing (no settings from the assistant, which may be absent, crashed or not yet
     * connected) judges by the default rules from its very first line: ordinary chat is {@code Passed} at once, as the
     * first line and after, and an addressed line is committed, even as the first line, with exactly its query and the
     * ordinary lines before it. Nothing is held, cancelled or delayed while the settings are unknown.
     */
    private void p6AdmitsFromTheFirstLine(Check c) {
        Gate g = unconfigured.get();
        Outcome first = g.offer(new Line("alex", "good morning", 1));
        Outcome second = g.offer(new Line("sam", "anyone online?", 2));
        Invocation asked = committed(g.offer(new Line("alex", "@mod what is the tps", 3)));
        Outcome after = g.offer(new Line("sam", "thanks", 4));
        Invocation firstLine = committed(unconfigured.get().offer(new Line("alex", "  @MOD hi ", 1)));
        boolean ok = first instanceof Outcome.Passed && second instanceof Outcome.Passed && after instanceof Outcome.Passed
                && asked != null && asked.query().equals("what is the tps")
                && texts(asked).equals(Arrays.asList("good morning", "anyone online?"))
                && firstLine != null && firstLine.query().equals("hi");
        c.that(ok, "P6:admits-from-the-first-line");
    }

    /**
     * P7: settings that arrive (or change) apply to the next line: the old trigger no longer addresses a line and the new
     * one does, the longest query, the snapshot's size and the bucket are the new ones.
     */
    private void p7SettingsApplyAtOnce(Check c) {
        Gate g = gates.apply(Rules.defaults());
        g.settings(Rules.defaults().withPrefixes(Arrays.asList("@recon")).withMaxQuery(12).withWindow(1, 1_000_000).withBucket(1, 1_000_000));
        boolean oldGone = !g.offer(new Line("alex", "@mod hi", 1)).addressed();
        g.offer(new Line("sam", "one", 2));
        g.offer(new Line("sam", "two", 3));
        Invocation named = committed(g.offer(new Line("alex", "@recon hi there", 4)));
        Outcome limited = g.offer(new Line("alex", "@recon and again", 5));
        Outcome tooLong = g.offer(new Line("kim", "@recon this is far too long a question", 6));
        boolean ok = oldGone && named != null && named.query().equals("hi there") && texts(named).equals(Arrays.asList("two"))
                && limited(limited) && tooLong instanceof Outcome.Rejected r && r.reason() == Outcome.Reason.TOO_LONG;
        c.that(ok, "P7:settings-apply-at-once");
    }

    /** P7: a settings change never brings back into a snapshot someone who opted out. */
    private void p7SettingsKeepOptOuts(Check c) {
        Gate g = gates.apply(Rules.defaults().withWindow(20, 1_000_000));
        g.offer(new Line("shy", "private words", 1));
        g.optOut("shy", true);
        g.settings(Rules.defaults().withPrefixes(Arrays.asList("@mod", "@recon")).withWindow(20, 1_000_000));
        g.offer(new Line("shy", "more private words", 2));
        g.offer(new Line("sam", "public words", 3));
        Invocation inv = committed(g.offer(new Line("alex", "@mod recap", 4)));
        c.that(g.optedOut("shy") && inv != null && texts(inv).equals(Arrays.asList("public words")), "P7:settings-keep-opt-outs");
    }

    private static boolean rejectedBy(Outcome o, Outcome.Reason reason, String trigger) {
        return o instanceof Outcome.Rejected r && r.reason() == reason && trigger.equals(r.trigger());
    }

    private static boolean limited(Outcome o) {
        return o instanceof Outcome.Rejected r && r.reason() == Outcome.Reason.RATE_LIMITED;
    }
}
