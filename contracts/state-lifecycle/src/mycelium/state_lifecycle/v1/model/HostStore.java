package mycelium.state_lifecycle.v1.model;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a provider keeps for the engine: which hosts are live and as what incarnation, the value cells of each incarnation,
 * and the stored (encoded) form of each durable value. {@code H} is the platform's own runtime object (a player, an
 * entity); this contract never looks inside it.
 *
 * <p>The primitives below are the provider's; the four accesses are defined here once, over them, and a provider that
 * overrides one must keep laws L2 and L5.
 *
 * <ul>
 *   <li>A cell belongs to exactly one incarnation. Ending an incarnation does not remove its cells: removing them is the
 *       engine's work (L4), so a cell left behind is visible, as the map entry keyed by a dead player object was.</li>
 *   <li>Stored forms belong to a host id and outlive every incarnation.</li>
 *   <li>The incarnation counter is per host id. {@link #next} is what the next {@link #begin} will give; {@link #retract}
 *       and {@link #restore} are the exact inverses of {@code begin} and {@code end}, for undo.</li>
 * </ul>
 */
public interface HostStore<H> {
    // --- identity ---------------------------------------------------------------------------------------------

    /** The host's live incarnation, if it has one. */
    Optional<HostRef> current(HostId id);

    /** The runtime object of that incarnation: present only while it is the host's current one (L2). */
    Optional<H> resolve(HostRef ref);

    /** The incarnation the next {@link #begin} of this host will have. */
    Incarnation next(HostId id);

    /** The host becomes live as {@code ref}, which must be {@code next(ref.host())}, with this runtime object. */
    void begin(HostRef ref, H handle);

    /** Undoes {@link #begin}: the host is not live and its counter is as before. */
    void retract(HostRef ref);

    /** The incarnation ends: the host is no longer live and the object is forgotten. Cells are left. */
    void end(HostRef ref);

    /** Undoes {@link #end}: the incarnation is live again with its object. */
    void restore(HostRef ref, H handle);

    /** Every live incarnation, ordered. */
    List<HostRef> live();

    // --- cells and stored forms -------------------------------------------------------------------------------

    /** The live value of a slot in exactly this incarnation, live or ended. */
    Optional<Object> cell(HostRef ref, SlotId slot);

    void putCell(HostRef ref, SlotId slot, Object value);

    void removeCell(HostRef ref, SlotId slot);

    /** The slots that hold a value in exactly this incarnation, live or ended. */
    Set<SlotId> cellSlots(HostRef ref);

    Optional<Object> stored(HostId host, SlotId slot);

    void putStored(HostId host, SlotId slot, Object encoded);

    void removeStored(HostId host, SlotId slot);

    /** Every host that has a stored form of the slot. */
    Set<HostId> storedHosts(SlotId slot);

    // --- access (defined once, over the primitives) ------------------------------------------------------------

    /** Whether the host has a value: {@code Gone}, {@code Absent} or {@code Present}. Never creates (L5). */
    default <T> Reading<T> peek(Slot<T> slot, HostRef ref) {
        return Access.peek(this, slot, ref);
    }

    /** The effective value: the stored one, else the declared initial value. {@code Gone} or {@code Present}. Never creates (L5). */
    default <T> Reading<T> read(Slot<T> slot, HostRef ref) {
        return Access.read(this, slot, ref);
    }

    /** The value, created as the initial value if absent: a write. {@code Gone}, {@code Present} or {@code Held}. */
    default <T> Reading<T> materialize(Slot<T> slot, HostRef ref, Tx tx) {
        return Access.materialize(this, slot, ref, tx);
    }

    /** Sets the value. {@code Gone}, {@code Present} (the new value) or {@code Held}. */
    default <T> Reading<T> write(Slot<T> slot, HostRef ref, T value, Tx tx) {
        return Access.write(this, slot, ref, value, tx);
    }
}
