package mycelium.chunk_hold.v1.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** The engine as it is (Tickets): a set of ticketed places, and a count of takes (a renewal takes again). */
public final class DedupedTickets<K> implements Tickets<K> {
    private final Set<K> ticketed = new LinkedHashSet<>();
    private int takes;

    @Override
    public void take(K place) {
        ticketed.add(place);
        takes++;
    }

    @Override
    public void drop(K place) {
        ticketed.remove(place);
    }

    public boolean ticketed(K place) {
        return ticketed.contains(place);
    }

    public int takes() {
        return takes;
    }
}
