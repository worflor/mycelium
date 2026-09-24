package mycelium.chunk_hold.v1.fixtures;

import mycelium.chunk_hold.v1.model.Keeper;
import mycelium.chunk_hold.v1.model.Tickets;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Broken: the engine alone. Every hold takes the ticket and every release drops it, so the first holder to let go takes
 * everyone's hold with it. This is what Somnium did until a copy session ending unloaded a room's chunks (E163).
 */
public final class EngineDirectKeeper implements Keeper<Integer, Integer> {
    private final Tickets<Integer> engine;
    private final Map<Integer, Set<Integer>> mine = new HashMap<>();
    private final Map<Integer, Long> leases = new HashMap<>();

    public EngineDirectKeeper(Tickets<Integer> engine) {
        this.engine = engine;
    }

    @Override
    public void hold(Integer place, Integer owner) {
        mine.computeIfAbsent(owner, o -> new HashSet<>()).add(place);
        engine.take(place);
    }

    @Override
    public void release(Integer place, Integer owner) {
        Set<Integer> ps = mine.get(owner);
        if (ps != null && ps.remove(place)) {
            engine.drop(place);
        }
    }

    @Override
    public void releaseAll(Integer owner) {
        Set<Integer> ps = mine.remove(owner);
        if (ps != null) {
            ps.forEach(engine::drop);
        }
    }

    @Override
    public void lease(Integer place, long until) {
        leases.merge(place, until, Math::max);
        engine.take(place);
    }

    @Override
    public void expire(long now) {
        for (Iterator<Map.Entry<Integer, Long>> it = leases.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                engine.drop(e.getKey());
            }
        }
    }

    @Override
    public void renew() {
        mine.values().forEach(ps -> ps.forEach(engine::take));
        leases.keySet().forEach(engine::take);
    }
}
