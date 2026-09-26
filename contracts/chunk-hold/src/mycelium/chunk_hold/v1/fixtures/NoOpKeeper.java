package mycelium.chunk_hold.v1.fixtures;

import mycelium.chunk_hold.v1.model.Keeper;
import mycelium.chunk_hold.v1.model.Tickets;

/**
 * Does nothing: takes no ticket, drops none. It breaks no law about letting go (nothing is ever dropped wrongly), which
 * is why it must be in the matrix: H1 says a held place has the engine's ticket, so a keeper that keeps nothing fails.
 */
public final class NoOpKeeper implements Keeper<Integer, Integer> {
    public NoOpKeeper(Tickets<Integer> engine) {
    }

    @Override
    public void hold(Integer place, Integer owner) {
    }

    @Override
    public void release(Integer place, Integer owner) {
    }

    @Override
    public void releaseAll(Integer owner) {
    }

    @Override
    public void lease(Integer place, long until) {
    }

    @Override
    public void expire(long now) {
    }

    @Override
    public void renew() {
    }
}
