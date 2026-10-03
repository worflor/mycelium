package mycelium.agent_link.v1.harness;

import mycelium.agent_link.v1.model.Backoff;
import mycelium.agent_link.v1.model.Handler;
import mycelium.agent_link.v1.model.Ledger;
import mycelium.agent_link.v1.model.Limits;
import mycelium.agent_link.v1.model.Link;
import mycelium.agent_link.v1.model.MemoryLedger;
import mycelium.agent_link.v1.model.Reply;
import mycelium.agent_link.v1.model.Request;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.HashMap;
import java.util.Map;

/** agent.link@1: who may ask, what is refused, what runs at most once, and how the mod reconnects (SPEC.md). */
public final class AgentLinkContractTests {
    /** Builds a link over a handler. */
    public interface Links {
        Link make(String token, Handler handler, Limits limits);
    }

    /** Builds a link over a handler and a ledger the caller keeps (L8). */
    public interface Handovers {
        Link make(String token, Handler handler, Limits limits, Ledger ledger);
    }

    /** A reconnect schedule: (base, cap, attempt, u) -> delay. */
    public interface Delays {
        long delay(long base, long cap, int attempt, double u);
    }

    static final String TOKEN = "s3cret-token";

    private final Links links;
    private final Delays delays;
    private final Handovers handovers;

    public AgentLinkContractTests(Links links, Delays delays, Handovers handovers) {
        this.links = links;
        this.delays = delays;
        this.handovers = handovers;
    }

    /** The reference schedule. */
    public static long reference(long base, long cap, int attempt, double u) {
        return new Backoff(base, cap).delay(attempt, u);
    }

    public Check run() {
        Check c = new Check();
        l1AtMostOnce(c);
        l1NoConcurrentTwin(c);
        l2SurvivesReconnect(c);
        l3NoWorkBeforeHello(c);
        l4Bounded(c);
        l5Oversize(c);
        l6Unknown(c);
        l7FailureRemembered(c);
        l8SurvivesHandover(c);
        l9FailureDiagnostic(c);
        p1ExecutesAndAnswers(c);
        p2Correlates(c);
        p3ReadsFresh(c);
        backoffLaws(c);
        return c;
    }

    /** A handler that counts runs per method; "boom" throws; "retry" re-enters the link with the same request. */
    static final class Counting implements Handler {
        final Map<String, Integer> runs = new HashMap<>();
        Link link;
        Request reentry;
        Reply inner;

        @Override
        public boolean knows(String method) {
            return !method.equals("nope");
        }

        @Override
        public String handle(String method, String params) throws Exception {
            runs.merge(method, 1, Integer::sum);
            if (method.equals("boom")) {
                throw new IllegalStateException("half done");
            }
            if (method.equals("retry") && reentry != null) {
                Request again = reentry;
                reentry = null;
                inner = link.receive(again);
            }
            return method + ":" + params + ":" + runs.get(method);
        }

        int runs(String method) {
            return runs.getOrDefault(method, 0);
        }
    }

    private Link fresh(Counting h, Limits limits) {
        Link l = links.make(TOKEN, h, limits);
        h.link = l;
        l.hello(TOKEN);
        return l;
    }

    private static Request give(String id) {
        return new Request(id, "give", true, 40, "{\"item\":\"diamond\"}");
    }

    /** L1: the same mutating id twice runs once; the second answer is the first, replayed. */
    private void l1AtMostOnce(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply a = l.receive(give("r1"));
        Reply b = l.receive(give("r1"));
        boolean ok = h.runs("give") == 1 && a instanceof Reply.Answered x && b instanceof Reply.Answered y
                && x.result().equals(y.result()) && !x.replayed() && y.replayed();
        c.that(ok, "L1:at-most-once");
    }

    /** L1: a retry that arrives while the first is still running is refused BUSY, not run beside it. */
    private void l1NoConcurrentTwin(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Request r = new Request("r9", "retry", true, 10, "{}");
        h.reentry = r;
        l.receive(r);
        boolean ok = h.runs("retry") == 1 && h.inner instanceof Reply.Refused f && f.refusal() == Reply.Refusal.BUSY;
        c.that(ok, "L1:no-concurrent-twin");
        Reply after = l.receive(r);
        c.that(after instanceof Reply.Answered a && a.replayed() && h.runs("retry") == 1, "L1:at-most-once");
    }

    /** L2: the connection drops before the answer is heard; the retry on the next connection does not run again. */
    private void l2SurvivesReconnect(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        l.receive(give("r2"));
        l.dropped();
        l.hello(TOKEN);
        Reply again = l.receive(give("r2"));
        c.that(h.runs("give") == 1 && again instanceof Reply.Answered a && a.replayed(), "L2:dedup-survives-reconnect");
    }

    /** L3: nothing runs before a good hello, after a bad one, or after a drop. */
    private void l3NoWorkBeforeHello(Check c) {
        Counting h = new Counting();
        Link l = links.make(TOKEN, h, Limits.defaults());
        h.link = l;
        Reply before = l.receive(give("a"));
        boolean bad = !l.hello("wrong");
        Reply afterBad = l.receive(give("b"));
        boolean good = l.hello(TOKEN);
        l.receive(give("c"));
        l.dropped();
        Reply afterDrop = l.receive(give("d"));
        boolean ok = bad && good && h.runs("give") == 1
                && refused(before, Reply.Refusal.UNAUTHENTICATED)
                && refused(afterBad, Reply.Refusal.UNAUTHENTICATED)
                && refused(afterDrop, Reply.Refusal.UNAUTHENTICATED);
        boolean nullHello = !l.hello(null) && refused(l.receive(give("e")), Reply.Refusal.UNAUTHENTICATED);
        c.that(ok && nullHello, "L3:no-work-before-hello");
    }

    /** L4: remembering is bounded, the oldest forgotten first. */
    private void l4Bounded(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, new Limits(1 << 20, 8));
        for (int i = 0; i < 100; i++) {
            l.receive(give("m" + i));
        }
        boolean bounded = l.remembered() <= 8;
        Reply newest = l.receive(give("m99"));
        l.receive(give("m0"));
        c.that(bounded && newest instanceof Reply.Answered a && a.replayed() && h.runs("give") == 101, "L4:memory-bounded");
    }

    /** L5: too large is refused and does not run. */
    private void l5Oversize(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, new Limits(100, 8));
        Reply big = l.receive(new Request("big", "give", true, 101, "{}"));
        Reply edge = l.receive(new Request("edge", "give", true, 100, "{}"));
        c.that(refused(big, Reply.Refusal.TOO_LARGE) && edge instanceof Reply.Answered && h.runs("give") == 1,
                "L5:oversize-refused");
    }

    /** L6: unknown methods and blank ids are refused without running. */
    private void l6Unknown(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply unknown = l.receive(new Request("u1", "nope", false, 5, "{}"));
        Reply blank = l.receive(new Request("  ", "give", true, 5, "{}"));
        c.that(refused(unknown, Reply.Refusal.UNKNOWN_METHOD) && h.runs("nope") == 0, "L6:unknown-refused");
        c.that(refused(blank, Reply.Refusal.INVALID) && h.runs("give") == 0, "L6:unknown-refused");
    }

    /** L7: a mutation that threw may have half-happened: its retry gets the same refusal and does not run. */
    private void l7FailureRemembered(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply first = l.receive(new Request("f1", "boom", true, 5, "{}"));
        Reply again = l.receive(new Request("f1", "boom", true, 5, "{}"));
        c.that(refused(first, Reply.Refusal.FAILED) && refused(again, Reply.Refusal.FAILED) && h.runs("boom") == 1,
                "L7:failure-is-remembered");
        c.that(first instanceof Reply.Refused f && "half done".equals(f.detail()), "L7:failure-carries-its-message");
    }

    /**
     * L8: the consumer is replaced (a new link, a new handler) over the ledger the old one used. What the old one
     * answered is not run again, what the old one is still running is refused BUSY and not run beside it, and what the
     * old one's handler threw is not run again.
     */
    private void l8SurvivesHandover(Check c) {
        Ledger ledger = new MemoryLedger(64);
        // answered before the handover
        Counting first = new Counting();
        Counting second = new Counting();
        Link a = handover(first, ledger);
        Link b = handover(second, ledger);
        a.receive(give("h1"));
        Reply again = b.receive(give("h1"));
        boolean answered = first.runs("give") == 1 && second.runs("give") == 0
                && again instanceof Reply.Answered y && y.replayed();

        // still running on the old side when the new side is asked; the old one then finishes
        Request r = new Request("h2", "retry", true, 10, "{}");
        first.link = b;
        first.reentry = r;
        a.receive(r);
        boolean busy = first.runs("retry") == 1 && second.runs("retry") == 0
                && first.inner instanceof Reply.Refused f && f.refusal() == Reply.Refusal.BUSY;
        Reply after = b.receive(r);
        boolean finished = second.runs("retry") == 0 && after instanceof Reply.Answered z && z.replayed();

        // a failure on the old side is the new side's answer too: it may have half happened
        a.receive(new Request("h3", "boom", true, 5, "{}"));
        Reply failed = b.receive(new Request("h3", "boom", true, 5, "{}"));
        boolean refused = second.runs("boom") == 0 && refused(failed, Reply.Refusal.FAILED);

        c.that(answered && busy && finished && refused, "L8:dedup-survives-handover");
    }

    private Link handover(Counting h, Ledger ledger) {
        Link l = handovers.make(TOKEN, h, Limits.defaults(), ledger);
        h.link = l;
        l.hello(TOKEN);
        return l;
    }

    private void l9FailureDiagnostic(Check c) {
        for (String message : new String[]{null, "", " \t", "the source read failed"}) {
            int[] runs = {0};
            Handler handler = new Handler() {
                @Override public boolean knows(String method) { return true; }
                @Override public String handle(String method, String params) throws Exception {
                    runs[0]++;
                    throw new java.util.concurrent.TimeoutException(message);
                }
            };
            Link link = links.make(TOKEN, handler, Limits.defaults());
            link.hello(TOKEN);
            Request request = new Request("diagnostic", "read", true, 5, "{}");
            Reply first = link.receive(request);
            link.dropped();
            link.hello(TOKEN);
            Reply retry = link.receive(request);
            String expected = message == null || message.isBlank()
                    ? "java.util.concurrent.TimeoutException" : message;
            c.that(first instanceof Reply.Refused failed && failed.refusal() == Reply.Refusal.FAILED
                            && expected.equals(failed.detail()) && first.equals(retry) && runs[0] == 1,
                    "L9:failure-has-diagnostic");
        }
    }

    /** P1: after a good hello, a known in-size request runs once and returns the handler's result. */
    private void p1ExecutesAndAnswers(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply r = l.receive(new Request("p1", "status", false, 5, "x"));
        c.that(r instanceof Reply.Answered a && a.result().equals("status:x:1") && !a.replayed() && h.runs("status") == 1,
                "P1:executes-and-answers");
    }

    /** P2: every reply carries its request's id, answered or refused. */
    private void p2Correlates(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, new Limits(50, 8));
        Seeded s = new Seeded(0xA11CEL);
        boolean ok = true;
        String[] methods = {"status", "give", "nope", "boom"};
        for (int i = 0; i < 400; i++) {
            String id = "id-" + s.nextInt(60);
            Request r = new Request(id, methods[s.nextInt(methods.length)], s.nextBoolean(), s.nextInt(80), "{}");
            ok &= l.receive(r).id().equals(id);
        }
        c.that(ok, "P2:reply-correlates");
    }

    /** P3: reads are run every time, so they are never stale. */
    private void p3ReadsFresh(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply a = l.receive(new Request("s", "status", false, 5, "x"));
        Reply b = l.receive(new Request("s", "status", false, 5, "x"));
        c.that(h.runs("status") == 2 && b instanceof Reply.Answered y && !y.replayed() && y.result().equals("status:x:2")
                && a instanceof Reply.Answered, "P3:reads-are-fresh");
    }

    /** B1-B3, P4 over a seeded sweep and the extremes. */
    private void backoffLaws(Check c) {
        long base = 500, cap = 30_000;
        Seeded s = new Seeded(0xBAC0FFL);
        boolean within = true;
        boolean grows = true;
        long prevCeiling = 0;
        for (int attempt = 0; attempt < 200; attempt++) {
            long top = 0;
            for (int k = 0; k < 40; k++) {
                long d = delays.delay(base, cap, attempt, k == 39 ? 0.999999 : s.nextDouble());
                within &= d >= 1 && d <= cap;
                top = Math.max(top, d);
            }
            grows &= top >= prevCeiling || top >= cap * 9 / 10;
            prevCeiling = Math.max(prevCeiling, top);
        }
        c.that(within, "B1:delay-within-bounds");
        c.that(grows && prevCeiling >= cap * 9 / 10, "B2:envelope-grows-to-cap");
        boolean forever = true;
        int[] far = {63, 64, 65, 1_000, 100_000, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int attempt : far) {
            long d = delays.delay(base, cap, attempt, 0.5);
            forever &= d >= 1 && d <= cap;
        }
        c.that(forever, "B3:never-gives-up");
        boolean prompt = true;
        for (int k = 0; k < 50; k++) {
            prompt &= delays.delay(base, cap, 0, s.nextDouble()) <= base;
        }
        c.that(prompt, "P4:first-retry-prompt");
    }

    private static boolean refused(Reply r, Reply.Refusal why) {
        return r instanceof Reply.Refused f && f.refusal() == why;
    }
}
