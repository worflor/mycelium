package mycelium.agent_link.v1.model;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** The reference ledger: bounded insertion-ordered replies and ids running or held after an indeterminate fatal error. */
public final class MemoryLedger implements Ledger {
    private final int capacity;
    private final Map<String, Reply> replies = new LinkedHashMap<>();
    private final Set<String> running = new HashSet<>();

    public MemoryLedger(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("a ledger remembers at least one reply");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized Reply enter(String id) {
        Reply seen = replies.get(id);
        if (seen != null) {
            return seen;
        }
        if (!running.add(id)) {
            return new Reply.Refused(id, Reply.Refusal.BUSY, "still running; ask again for its answer");
        }
        return null;
    }

    @Override
    public synchronized void finish(String id, Reply reply) {
        replies.put(id, reply);
        running.remove(id);
        while (replies.size() > capacity) {
            replies.remove(replies.keySet().iterator().next());
        }
    }

    @Override
    public synchronized void abandon(String id) {
        running.remove(id);
    }

    @Override
    public synchronized int size() {
        return replies.size();
    }
}
