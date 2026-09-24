package mycelium.chunk_hold.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * chunk.hold@1's ledger (SPEC.md): who holds each place. The engine keeps one ticket per place, not one per holder, so a
 * hold is owned here and the engine's ticket follows the ledger: taken when a place becomes held, dropped only when
 * nothing holds it.
 *
 * <p>Two kinds of holder: an <b>owner</b> holds until it lets go ({@link #hold}, {@link #release}, {@link #releaseAll});
 * a <b>lease</b> holds while it keeps being asked for and lapses by itself ({@link #lease}, {@link #expire}). A place is
 * held while it has an owner or a lease that has not lapsed.
 *
 * <p>Each mutation answers whether the engine must act: {@code true} from {@link #hold} and {@link #lease} means take
 * the ticket; {@code true} from {@link #release}, and every place {@link #releaseAll} and {@link #expire} return, means
 * drop it. {@link LedgerKeeper} does exactly that over a {@link Tickets} engine.
 */
public final class Holds<K, O> {
    private final Map<K, Set<O>> owners = new LinkedHashMap<>();
    private final Map<O, Set<K>> of = new LinkedHashMap<>();
    private final Map<K, Long> leases = new LinkedHashMap<>();

    /** An owner takes hold; true when the place was not held before (take the ticket). */
    public boolean hold(K place, O owner) {
        boolean was = held(place);
        owners.computeIfAbsent(place, k -> new LinkedHashSet<>()).add(owner);
        of.computeIfAbsent(owner, o -> new LinkedHashSet<>()).add(place);
        return !was;
    }

    /** An owner lets go; true when nothing holds the place any more (drop the ticket). A stranger's release is nothing. */
    public boolean release(K place, O owner) {
        Set<O> os = owners.get(place);
        if (os == null || !os.remove(owner)) {
            return false;
        }
        if (os.isEmpty()) {
            owners.remove(place);
        }
        Set<K> ks = of.get(owner);
        if (ks != null && ks.remove(place) && ks.isEmpty()) {
            of.remove(owner);
        }
        return !held(place);
    }

    /** Everything an owner held, let go: the places nothing holds any more. */
    public List<K> releaseAll(O owner) {
        List<K> dropped = new ArrayList<>();
        Set<K> ks = of.get(owner);
        if (ks != null) {
            for (K k : new ArrayList<>(ks)) {
                if (release(k, owner)) {
                    dropped.add(k);
                }
            }
        }
        return dropped;
    }

    /** A lease until {@code until}, extended if asked for again (never shortened); true when the place was not held before. */
    public boolean lease(K place, long until) {
        boolean was = held(place);
        Long had = leases.get(place);
        leases.put(place, had == null ? until : Math.max(had, until));
        return !was;
    }

    /** Leases whose time has come lapse: the places nothing holds any more. */
    public List<K> expire(long now) {
        List<K> dropped = new ArrayList<>();
        for (Iterator<Map.Entry<K, Long>> it = leases.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<K, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                if (!owners.containsKey(e.getKey())) {
                    dropped.add(e.getKey());
                }
            }
        }
        return dropped;
    }

    public boolean held(K place) {
        return owners.containsKey(place) || leases.containsKey(place);
    }

    /** Every held place, in the order first taken: the tickets to renew. */
    public List<K> held() {
        Set<K> all = new LinkedHashSet<>(owners.keySet());
        all.addAll(leases.keySet());
        return new ArrayList<>(all);
    }

    /** Who holds a place (leases are nobody's). */
    public Set<O> owners(K place) {
        Set<O> os = owners.get(place);
        return os == null ? Collections.<O>emptySet() : Collections.unmodifiableSet(os);
    }

    /** Everything, forgotten (the engine's tickets go with it: a server stopping). */
    public void clear() {
        owners.clear();
        of.clear();
        leases.clear();
    }
}
