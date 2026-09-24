package mycelium.chunk_hold.v1.model;

/** chunk.hold@1's reference Keeper: an owner ledger ({@link Holds}) in front of the engine. */
public final class LedgerKeeper<K, O> implements Keeper<K, O> {
    private final Holds<K, O> holds = new Holds<>();
    private final Tickets<K> engine;

    public LedgerKeeper(Tickets<K> engine) {
        this.engine = engine;
    }

    @Override
    public void hold(K place, O owner) {
        if (holds.hold(place, owner)) {
            engine.take(place);
        }
    }

    @Override
    public void release(K place, O owner) {
        if (holds.release(place, owner)) {
            engine.drop(place);
        }
    }

    @Override
    public void releaseAll(O owner) {
        for (K k : holds.releaseAll(owner)) {
            engine.drop(k);
        }
    }

    @Override
    public void lease(K place, long until) {
        if (holds.lease(place, until)) {
            engine.take(place);
        }
    }

    @Override
    public void expire(long now) {
        for (K k : holds.expire(now)) {
            engine.drop(k);
        }
    }

    @Override
    public void renew() {
        for (K k : holds.held()) {
            engine.take(k);
        }
    }

    public Holds<K, O> ledger() {
        return holds;
    }
}
