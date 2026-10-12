package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Incarnation;
import mycelium.state_lifecycle.v1.model.SlotId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reference host store: everything in memory, ordered, so that a dump of it is the same text on every Java. Ending an
 * incarnation leaves its cells, as a map keyed by a dead object would; only the engine removes them.
 */
public class MemoryHostStore<H> implements HostStore<H> {
    protected final Map<HostId, Long> issued = new TreeMap<>();
    protected final Map<HostId, HostRef> live = new TreeMap<>();
    protected final Map<HostRef, H> handles = new TreeMap<>();
    protected final Map<HostRef, Map<SlotId, Object>> cells = new TreeMap<>();
    protected final Map<HostId, Map<SlotId, Object>> stored = new TreeMap<>();

    @Override
    public Optional<HostRef> current(HostId id) {
        return Optional.ofNullable(live.get(id));
    }

    @Override
    public Optional<H> resolve(HostRef ref) {
        return ref.equals(live.get(ref.host())) ? Optional.of(handles.get(ref)) : Optional.<H>empty();
    }

    @Override
    public Incarnation next(HostId id) {
        Long last = issued.get(id);
        return last == null ? Incarnation.FIRST : new Incarnation(last).next();
    }

    @Override
    public void begin(HostRef ref, H handle) {
        if (live.containsKey(ref.host()) || !ref.incarnation().equals(next(ref.host()))) {
            throw new IllegalStateException("cannot begin " + ref);
        }
        issued.put(ref.host(), ref.incarnation().value());
        live.put(ref.host(), ref);
        handles.put(ref, handle);
    }

    @Override
    public void retract(HostRef ref) {
        if (!ref.equals(live.get(ref.host())) || ref.incarnation().value() != issued.get(ref.host())) {
            throw new IllegalStateException("cannot retract " + ref);
        }
        if (ref.incarnation().value() == 1) {
            issued.remove(ref.host());
        } else {
            issued.put(ref.host(), ref.incarnation().value() - 1);
        }
        live.remove(ref.host());
        handles.remove(ref);
    }

    @Override
    public void end(HostRef ref) {
        if (!ref.equals(live.get(ref.host()))) {
            throw new IllegalStateException("cannot end " + ref);
        }
        live.remove(ref.host());
        handles.remove(ref);
    }

    @Override
    public void restore(HostRef ref, H handle) {
        if (live.containsKey(ref.host())) {
            throw new IllegalStateException("cannot restore " + ref);
        }
        live.put(ref.host(), ref);
        handles.put(ref, handle);
    }

    @Override
    public List<HostRef> live() {
        return Collections.unmodifiableList(new ArrayList<>(live.values()));
    }

    @Override
    public Optional<Object> cell(HostRef ref, SlotId slot) {
        Map<SlotId, Object> m = cells.get(ref);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(slot));
    }

    @Override
    public void putCell(HostRef ref, SlotId slot, Object value) {
        cells.computeIfAbsent(ref, k -> new TreeMap<>()).put(slot, value);
    }

    @Override
    public void removeCell(HostRef ref, SlotId slot) {
        Map<SlotId, Object> m = cells.get(ref);
        if (m != null) {
            m.remove(slot);
            if (m.isEmpty()) {
                cells.remove(ref);
            }
        }
    }

    @Override
    public Set<SlotId> cellSlots(HostRef ref) {
        Map<SlotId, Object> m = cells.get(ref);
        return m == null ? new TreeSet<>() : new TreeSet<>(m.keySet());
    }

    @Override
    public Optional<Object> stored(HostId host, SlotId slot) {
        Map<SlotId, Object> m = stored.get(host);
        return m == null ? Optional.empty() : Optional.ofNullable(m.get(slot));
    }

    @Override
    public void putStored(HostId host, SlotId slot, Object encoded) {
        stored.computeIfAbsent(host, k -> new TreeMap<>()).put(slot, encoded);
    }

    @Override
    public void removeStored(HostId host, SlotId slot) {
        Map<SlotId, Object> m = stored.get(host);
        if (m != null) {
            m.remove(slot);
            if (m.isEmpty()) {
                stored.remove(host);
            }
        }
    }

    @Override
    public Set<HostId> storedHosts(SlotId slot) {
        Set<HostId> out = new TreeSet<>();
        for (Map.Entry<HostId, Map<SlotId, Object>> e : stored.entrySet()) {
            if (e.getValue().containsKey(slot)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** What a server restart leaves: the stored forms, and nothing live. */
    public void adoptStorage(MemoryHostStore<?> before) {
        for (Map.Entry<HostId, Map<SlotId, Object>> e : before.stored.entrySet()) {
            stored.put(e.getKey(), new TreeMap<>(e.getValue()));
        }
    }

    /** All of it as text: counters, live hosts, every cell (ended incarnations too), every stored form. */
    public String dump() {
        StringBuilder b = new StringBuilder();
        b.append("issued=").append(issued).append('\n');
        b.append("live=").append(live.values()).append('\n');
        b.append("cells=").append(cells).append('\n');
        b.append("stored=").append(stored).append('\n');
        return b.toString();
    }
}
