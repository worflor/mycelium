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
        l10FailedRefusalReplay(c);
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
            if (method.equals("stack")) {
                throw new StackOverflowError();
            }
            if (method.equals("error")) {
                throw new Error("recoverable error");
            }
            if (method.equals("fatal")) {
                throw new OutOfMemoryError("simulated fatal error");
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
        boolean ok = h.runs("retry") == 1 && h.inner instanceof Reply.Refused f
                && f.refusal() == Reply.Refusal.BUSY && !f.replayed();
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
                && refused(afterDrop, Reply.Refusal.UNAUTHENTICATED)
                && before instanceof Reply.Refused beforeHello && !beforeHello.replayed()
                && afterBad instanceof Reply.Refused badHello && !badHello.replayed()
                && afterDrop instanceof Reply.Refused dropped && !dropped.replayed();
        boolean nullAccepted = !l.hello(null);
        Reply nullHelloReply = l.receive(give("e"));
        boolean nullHello = nullAccepted && refused(nullHelloReply, Reply.Refusal.UNAUTHENTICATED)
                && nullHelloReply instanceof Reply.Refused refusedNull && !refusedNull.replayed();
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
        c.that(refused(big, Reply.Refusal.TOO_LARGE) && big instanceof Reply.Refused oversized && !oversized.replayed()
                        && edge instanceof Reply.Answered && h.runs("give") == 1,
                "L5:oversize-refused");
    }

    /** L6: unknown methods and blank ids are refused without running. */
    private void l6Unknown(Check c) {
        Counting h = new Counting();
        Link l = fresh(h, Limits.defaults());
        Reply unknown = l.receive(new Request("u1", "nope", false, 5, "{}"));
        Reply blank = l.receive(new Request("  ", "give", true, 5, "{}"));
        c.that(refused(unknown, Reply.Refusal.UNKNOWN_METHOD)
                        && unknown instanceof Reply.Refused unknownRefusal && !unknownRefusal.replayed()
                        && h.runs("nope") == 0, "L6:unknown-refused");
        c.that(refused(blank, Reply.Refusal.INVALID)
                        && blank instanceof Reply.Refused invalidRefusal && !invalidRefusal.replayed()
                        && h.runs("give") == 0, "L6:unknown-refused");
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

        Counting errors = new Counting();
        Link errorLink = fresh(errors, Limits.defaults());
        Request stack = new Request("stack", "stack", true, 5, "{}");
        Reply stackFirst = receiveError(errorLink, stack);
        Reply stackAgain = receiveError(errorLink, stack);
        c.that(stackFirst instanceof Reply.Refused f && f.refusal() == Reply.Refusal.FAILED
                        && "java.lang.StackOverflowError".equals(f.detail())
                        && stackAgain instanceof Reply.Refused retryStack && f.id().equals(retryStack.id())
                        && f.refusal() == retryStack.refusal() && f.detail().equals(retryStack.detail())
                        && errors.runs("stack") == 1,
                "L7:failure-is-remembered recoverable-error");

        Request plain = new Request("plain-error", "error", true, 5, "{}");
        Reply plainFirst = receiveError(errorLink, plain);
        Reply plainAgain = receiveError(errorLink, plain);
        c.that(plainFirst instanceof Reply.Refused f && f.refusal() == Reply.Refusal.FAILED
                        && "recoverable error".equals(f.detail())
                        && plainAgain instanceof Reply.Refused retryError && f.id().equals(retryError.id())
                        && f.refusal() == retryError.refusal() && f.detail().equals(retryError.detail())
                        && errors.runs("error") == 1,
                "L7:failure-is-remembered nonfatal-error");

        Request fatal = new Request("fatal", "fatal", true, 5, "{}");
        boolean escaped = receiveOutOfMemory(errorLink, fatal);
        Reply afterFatal = retryOutOfMemory(errorLink, fatal);
        c.that(escaped && refused(afterFatal, Reply.Refusal.BUSY) && errors.runs("fatal") == 1,
                "L7:fatal-error-retains-claim");
    }

    private static Reply receiveError(Link link, Request request) {
        try {
            return link.receive(request);
        } catch (Error escaped) {
            return null;
        }
    }

    private static boolean receiveOutOfMemory(Link link, Request request) {
        try {
            link.receive(request);
            return false;
        } catch (OutOfMemoryError escaped) {
            return true;
        }
    }

    private static Reply retryOutOfMemory(Link link, Request request) {
        try {
            return link.receive(request);
        } catch (OutOfMemoryError escaped) {
            return null;
        }
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

        Request stack = new Request("h4", "stack", true, 5, "{}");
        Reply stackFailed = receiveError(a, stack);
        Reply stackRetry = receiveError(b, stack);
        boolean stackRemembered = stackFailed instanceof Reply.Refused f && f.refusal() == Reply.Refusal.FAILED
                && stackRetry instanceof Reply.Refused replayed && f.id().equals(replayed.id())
                && f.refusal() == replayed.refusal() && f.detail().equals(replayed.detail())
                && first.runs("stack") == 1 && second.runs("stack") == 0;

        Request fatal = new Request("h5", "fatal", true, 5, "{}");
        boolean fatalEscaped = receiveOutOfMemory(a, fatal);
        Reply sameSession = retryOutOfMemory(a, fatal);
        Reply replacement = retryOutOfMemory(b, fatal);
        boolean fatalHeld = fatalEscaped && refused(sameSession, Reply.Refusal.BUSY)
                && refused(replacement, Reply.Refusal.BUSY) && first.runs("fatal") == 1;

        c.that(answered && busy && finished && refused && stackRemembered && fatalHeld,
                "L8:dedup-survives-handover");
    }

    /** L10: cached failed mutations carry replay provenance after reconnect and consumer handover. */
    private void l10FailedRefusalReplay(Check c) {
        Counting reconnectHandler = new Counting();
        Link reconnect = fresh(reconnectHandler, Limits.defaults());
        Request reconnectRequest = new Request("failed-reconnect", "boom", true, 5, "{}");
        Reply reconnectInitial = reconnect.receive(reconnectRequest);
        reconnect.dropped();
        reconnect.hello(TOKEN);
        Reply reconnectRetry = reconnect.receive(reconnectRequest);
        boolean reconnected = reconnectInitial instanceof Reply.Refused freshFailure
                && freshFailure.refusal() == Reply.Refusal.FAILED && !freshFailure.replayed()
                && reconnectRetry instanceof Reply.Refused cachedFailure
                && cachedFailure.refusal() == Reply.Refusal.FAILED && cachedFailure.replayed()
                && reconnectHandler.runs("boom") == 1;

        Ledger ledger = new MemoryLedger(16);
        Counting original = new Counting();
        Counting replacement = new Counting();
        Link first = handover(original, ledger);
        Link second = handover(replacement, ledger);
        Request request = new Request("failed-handover", "boom", true, 5, "{}");
        Reply initial = first.receive(request);
        first.dropped();
        Reply retry = second.receive(request);
        boolean ok = initial instanceof Reply.Refused fresh && fresh.refusal() == Reply.Refusal.FAILED
                && !fresh.replayed() && retry instanceof Reply.Refused cached
                && cached.refusal() == Reply.Refusal.FAILED && cached.replayed()
                && original.runs("boom") == 1 && replacement.runs("boom") == 0;
        c.that(reconnected && ok, "L10:failed-refusal-replayed");
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
                    && expected.equals(failed.detail())
                    && retry instanceof Reply.Refused replayed
                    && expected.equals(replayed.detail()) && runs[0] == 1,
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
