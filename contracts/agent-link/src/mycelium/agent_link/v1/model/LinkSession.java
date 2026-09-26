package mycelium.agent_link.v1.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The reference link. Authentication is per connection; remembered replies to mutating requests are per session, in
 * an insertion-ordered map trimmed from the oldest end. Thread-safe: one connection thread at a time is the expected
 * use, but {@code receive} and {@code dropped} may race and are serialised.
 */
public final class LinkSession implements Link {
    private final byte[] token;
    private final Handler handler;
    private final Limits limits;
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final Set<String> running = new HashSet<>();
    private boolean authenticated;

    public LinkSession(String token, Handler handler, Limits limits) {
        if (token == null || token.isEmpty()) {
            throw new IllegalArgumentException("a link needs a non-empty token");
        }
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.handler = handler;
        this.limits = limits;
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
                Reply seen = replies.get(id);
                if (seen != null) {
                    return seen instanceof Reply.Answered a ? a.replay() : seen;
                }
                if (!running.add(id)) {
                    return new Reply.Refused(id, Reply.Refusal.BUSY, "still running; ask again for its answer");
                }
            }
        }
        // the handler runs outside the lock: it may wait on the server thread, and dropped() must not block on it
        Reply reply;
        try {
            reply = new Reply.Answered(id, handler.handle(r.method(), r.params()), false);
        } catch (Exception | LinkageError | AssertionError e) {
            reply = new Reply.Refused(id, Reply.Refusal.FAILED, String.valueOf(e.getMessage()));
        } catch (Error e) { // out of memory and the like: nothing is remembered, and the id is free to retry
            if (r.mutating()) {
                synchronized (this) {
                    running.remove(id);
                }
            }
            throw e;
        }
        if (r.mutating()) {
            synchronized (this) {
                running.remove(id);
                replies.put(id, reply);
                while (replies.size() > limits.rememberReplies()) {
                    replies.remove(replies.keySet().iterator().next());
                }
            }
        }
        return reply;
    }

    @Override
    public synchronized void dropped() {
        authenticated = false;
    }

    @Override
    public synchronized int remembered() {
        return replies.size();
    }
}
