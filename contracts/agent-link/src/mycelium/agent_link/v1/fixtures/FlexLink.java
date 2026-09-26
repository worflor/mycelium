package mycelium.agent_link.v1.fixtures;

import mycelium.agent_link.v1.model.Handler;
import mycelium.agent_link.v1.model.Limits;
import mycelium.agent_link.v1.model.Link;
import mycelium.agent_link.v1.model.Reply;
import mycelium.agent_link.v1.model.Request;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An independent link with one knob per way to get the contract wrong (SPEC.md, rejection matrix). With no flaw it is a
 * second correct implementation.
 */
public final class FlexLink implements Link {
    public enum Flaw { NONE, NO_OP, FORGETFUL, RACING, AMNESIAC, TRUSTING, HOARDING, GLUTTON, GUESSING, RETRYING, MISROUTED, STALE_READS }

    private final String token;
    private final Handler handler;
    private final Limits limits;
    private final Flaw flaw;
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final Set<String> running = new HashSet<>();
    private boolean authed;

    public FlexLink(String token, Handler handler, Limits limits, Flaw flaw) {
        this.token = token;
        this.handler = handler;
        this.limits = limits;
        this.flaw = flaw;
    }

    @Override
    public boolean hello(String offered) {
        authed = token.equals(offered);
        return authed;
    }

    @Override
    public Reply receive(Request r) {
        String id = flaw == Flaw.MISROUTED ? r.id() + "'" : r.id();
        if (!authed && flaw != Flaw.TRUSTING) {
            return new Reply.Refused(id, Reply.Refusal.UNAUTHENTICATED, "");
        }
        if (id == null || id.trim().isEmpty() || r.method() == null) {
            return new Reply.Refused(String.valueOf(id), Reply.Refusal.INVALID, "");
        }
        if (r.bytes() > limits.maxFrameBytes() && flaw != Flaw.GLUTTON) {
            return new Reply.Refused(id, Reply.Refusal.TOO_LARGE, "");
        }
        if (!handler.knows(r.method()) && flaw != Flaw.GUESSING) {
            return new Reply.Refused(id, Reply.Refusal.UNKNOWN_METHOD, "");
        }
        if (flaw == Flaw.NO_OP) {
            return new Reply.Answered(id, "ok", false);
        }
        boolean remember = (r.mutating() || flaw == Flaw.STALE_READS) && flaw != Flaw.FORGETFUL;
        if (remember) {
            Reply seen = replies.get(id);
            if (seen != null) {
                return seen instanceof Reply.Answered a ? a.replay() : seen;
            }
            if (flaw != Flaw.RACING && !running.add(id)) {
                return new Reply.Refused(id, Reply.Refusal.BUSY, "");
            }
        }
        Reply reply;
        try {
            reply = new Reply.Answered(id, handler.handle(r.method(), r.params()), false);
        } catch (Exception e) {
            reply = new Reply.Refused(id, Reply.Refusal.FAILED, String.valueOf(e.getMessage()));
        }
        if (remember) {
            running.remove(id);
            if (!(flaw == Flaw.RETRYING && reply instanceof Reply.Refused)) {
                replies.put(id, reply);
            }
            if (flaw != Flaw.HOARDING) {
                List<String> keys = new ArrayList<>(replies.keySet());
                for (int i = 0; i < keys.size() - limits.rememberReplies(); i++) {
                    replies.remove(keys.get(i));
                }
            }
        }
        return reply;
    }

    @Override
    public void dropped() {
        authed = false;
        if (flaw == Flaw.AMNESIAC) {
            replies.clear();
        }
    }

    @Override
    public int remembered() {
        return replies.size();
    }
}
