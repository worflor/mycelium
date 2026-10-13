package mycelium.bridges.lifecycle;

import mycelium.item_component.v1.model.TagNode;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.Incarnation;
import mycelium.state_lifecycle.v1.model.SlotId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The bridge's {@link HostStore}: the live incarnations of one kind of host (the platform's player objects, or its server), the
 * value cells of each incarnation, and the stored form of each durable value, which is kept in the world's {@link Saved}. It is the
 * contract's reference store over the game's objects: ending an incarnation leaves its cells, because removing them is the
 * engine's work (L4), and a cell left behind is then visible to a check.
 *
 * <p>Everything here happens on the server thread. A ref is valid within one run of the server and is not persisted.
 */
final class Store<H> implements HostStore<H> {
    private final Map<HostId, Long> issued = new TreeMap<>();
    private final Map<HostId, HostRef> live = new TreeMap<>();
    private final Map<HostRef, H> handles = new TreeMap<>();
    private final Map<H, HostRef> byHandle = new IdentityHashMap<>();
    private final Map<HostRef, Map<SlotId, Object>> cells = new TreeMap<>();
    private final Saved saved;
    private final Runnable changed;

    /** @param saved the forms the world keeps; @param changed told when one is put or removed, so the world saves them */
    Store(Saved saved, Runnable changed) {
        this.saved = saved;
        this.changed = changed;
    }

    /** The incarnation {@code handle} is, if it is the object a live host resolves to: an object a respawn replaced, or that logged out, is none. */
    Optional<HostRef> currentOf(H handle) {
        HostRef ref = handle == null ? null : byHandle.get(handle);
        return ref != null && handles.get(ref) == handle ? Optional.of(ref) : Optional.empty();
    }

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
        byHandle.put(handle, ref);
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
        byHandle.remove(handles.remove(ref));
    }

    @Override
    public void end(HostRef ref) {
        if (!ref.equals(live.get(ref.host()))) {
            throw new IllegalStateException("cannot end " + ref);
        }
        live.remove(ref.host());
        byHandle.remove(handles.remove(ref));
    }

    @Override
    public void restore(HostRef ref, H handle) {
        if (live.containsKey(ref.host())) {
            throw new IllegalStateException("cannot restore " + ref);
        }
        live.put(ref.host(), ref);
        handles.put(ref, handle);
        byHandle.put(handle, ref);
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
        return saved.form(host, slot).map(f -> (Object) f);
    }

    @Override
    public void putStored(HostId host, SlotId slot, Object encoded) {
        if (!(encoded instanceof TagNode form)) {
            throw new IllegalStateException("slot " + slot + " stored a " + (encoded == null ? "null" : encoded.getClass().getName())
                    + ": the lifecycle bridge saves item.component@1 TagNode forms (a slot made with ComponentSlotCodec)");
        }
        saved.put(host, slot, form);
        changed.run();
    }

    @Override
    public void removeStored(HostId host, SlotId slot) {
        if (saved.form(host, slot).isPresent()) {
            saved.remove(host, slot);
            changed.run();
        }
    }

    @Override
    public Set<HostId> storedHosts(SlotId slot) {
        return saved.hostsWith(slot);
    }

    /** A stored form that does not decode is kept apart, in the world's data, and the host comes without it. */
    void quarantine(HostId host, SlotId slot) {
        saved.quarantine(host, slot);
        changed.run();
    }

    /** How many value cells are held, in any incarnation (a check that nothing of an ended host is kept). */
    int cellCount() {
        int n = 0;
        for (Map<SlotId, Object> m : cells.values()) {
            n += m.size();
        }
        return n;
    }
}
