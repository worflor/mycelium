package mycelium.agent_link.v1.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The reference link. Authentication is per connection; what is remembered about mutating requests is the ledger's,
 * which outlives the connection and, when the consumer passes one in, the session itself (L8). Thread-safe: one
 * connection thread at a time is the expected use, but {@code receive} and {@code dropped} may race and are serialised.
 */
public final class LinkSession implements Link {
    private final byte[] token;
    private final Handler handler;
    private final Limits limits;
    private final Ledger ledger;
    private boolean authenticated;

    /** A session with a ledger of its own: it remembers for as long as it lives. */
    public LinkSession(String token, Handler handler, Limits limits) {
        this(token, handler, limits, new MemoryLedger(limits.rememberReplies()));
    }

    /**
     * A session over {@code ledger}: it remembers, and finds running, whatever any session over the same ledger did.
     * The ledger should remember at least {@code limits.rememberReplies()} replies (L4 is the ledger's to keep).
     */
    public LinkSession(String token, Handler handler, Limits limits, Ledger ledger) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("a link needs a non-empty token");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
        this.limits = limits;
        this.ledger = ledger;
    }

    @Override
    public synchronized boolean hello(String offered) {
        authenticated = offered != null && MessageDigest.isEqual(token, offered.getBytes(StandardCharsets.UTF_8));
        return authenticated;
    }

    @Override
    public Reply receive(Request r) {
        String id = r.id() == null ? "" : r.id();
        synchronized (this) {
            if (!authenticated) {
                return new Reply.Refused(id, Reply.Refusal.UNAUTHENTICATED, "hello first");
            }
            if (id.trim().isEmpty() || r.method() == null) {
                return new Reply.Refused(id, Reply.Refusal.INVALID, "a request needs an id and a method");
            }
            if (r.bytes() > limits.maxFrameBytes()) {
                return new Reply.Refused(id, Reply.Refusal.TOO_LARGE,
                        r.bytes() + " bytes > " + limits.maxFrameBytes());
            }
            if (!handler.knows(r.method())) {
                return new Reply.Refused(id, Reply.Refusal.UNKNOWN_METHOD, r.method());
            }
            if (r.mutating()) {
                Reply seen = ledger.enter(id);
                if (seen != null) {
                    return seen instanceof Reply.Answered a ? a.replay() : seen;
                }
            }
        }
        // the handler runs outside the lock: it may wait on the server thread, and dropped() must not block on it
        Reply reply;
        try {
            reply = new Reply.Answered(id, handler.handle(r.method(), r.params()), false);
        } catch (Exception | LinkageError | AssertionError e) {
            String message = e.getMessage();
            reply = new Reply.Refused(id, Reply.Refusal.FAILED,
                    message == null || message.isBlank() ? e.getClass().getName() : message);
        } catch (Error e) {
            if (fatal(e)) {
                throw e;
            }
            String message = e.getMessage();
            reply = new Reply.Refused(id, Reply.Refusal.FAILED,
                    message == null || message.isBlank() ? e.getClass().getName() : message);
        }
        if (r.mutating()) {
            ledger.finish(id, reply);
        }
        return reply;
    }

    @SuppressWarnings("removal")
    private static boolean fatal(Error error) {
        return error instanceof ThreadDeath
                || error instanceof VirtualMachineError && !(error instanceof StackOverflowError);
    }

    @Override
    public synchronized void dropped() {
        authenticated = false;
    }

    @Override
    public int remembered() {
        return ledger.size();
    }
}
