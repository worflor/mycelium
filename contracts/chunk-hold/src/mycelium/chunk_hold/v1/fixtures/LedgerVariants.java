package mycelium.chunk_hold.v1.fixtures;

import mycelium.chunk_hold.v1.model.Holds;
import mycelium.chunk_hold.v1.model.Keeper;
import mycelium.chunk_hold.v1.model.Tickets;

import java.util.HashMap;
import java.util.Map;

/** Broken keepers that do keep a ledger, each wrong in one way (SPEC.md, rejection matrix). */
public final class LedgerVariants {
    private LedgerVariants() {
    }

    /** A ledger that takes tickets and never drops one: nothing it held ever unloads (a leak). */
    public static Keeper<Integer, Integer> neverDrops(Tickets<Integer> engine) {
        return new Base(engine) {
            @Override
            void drop(Integer place) {
            }
        };
    }

    /** Anyone's release clears the place (everyone's hold on it), owner or not. */
    public static Keeper<Integer, Integer> strangerDrops(Tickets<Integer> engine) {
        return new Base(engine) {
            @Override
            public void release(Integer place, Integer owner) {
                for (Integer o : new java.util.ArrayList<>(holds.owners(place))) {
                    holds.release(place, o);
                }
                if (!holds.held(place)) {
                    engine.drop(place);
                }
                if (holds.owners(place).isEmpty()) {
                    engine.drop(place); // and a lease with it
                }
            }
        };
    }

    /** Leases never lapse: whatever was ever asked for stays loaded forever. */
    public static Keeper<Integer, Integer> eternalLeases(Tickets<Integer> engine) {
        return new Base(engine) {
            @Override
            public void expire(long now) {
            }
        };
    }

    /** A lease asked for again is replaced, not extended: a shorter ask cuts it short. */
    public static Keeper<Integer, Integer> shortLeases(Tickets<Integer> engine) {
        return new Base(engine) {
            private final Map<Integer, Long> latest = new HashMap<>();

            @Override
            public void lease(Integer place, long until) {
                latest.put(place, until);
                super.lease(place, until);
            }

            @Override
            public void expire(long now) {
                for (Map.Entry<Integer, Long> e : new HashMap<>(latest).entrySet()) {
                    if (e.getValue() <= now) {
                        latest.remove(e.getKey());
                        if (holds.owners(e.getKey()).isEmpty()) {
                            engine.drop(e.getKey());
                        }
                    }
                }
                super.expire(now);
            }
        };
    }

    /** Renewal remembers owners and forgets leases, so a lease's ticket times out under it. */
    public static Keeper<Integer, Integer> renewsOwnersOnly(Tickets<Integer> engine) {
        return new Base(engine) {
            private final java.util.Set<Integer> owned = new java.util.HashSet<>();

            @Override
            public void hold(Integer place, Integer owner) {
                owned.add(place);
                super.hold(place, owner);
            }

            @Override
            public void renew() {
                for (Integer p : holds.held()) {
                    if (owned.contains(p) && !holds.owners(p).isEmpty()) {
                        engine.take(p);
                    }
                }
            }
        };
    }

    /** The reference wiring, open for one thing to go wrong. */
    abstract static class Base implements Keeper<Integer, Integer> {
        final Holds<Integer, Integer> holds = new Holds<>();
        final Tickets<Integer> engine;

        Base(Tickets<Integer> engine) {
            this.engine = engine;
        }

        void drop(Integer place) {
            engine.drop(place);
        }

        @Override
        public void hold(Integer place, Integer owner) {
            if (holds.hold(place, owner)) {
                engine.take(place);
            }
        }

        @Override
        public void release(Integer place, Integer owner) {
            if (holds.release(place, owner)) {
                drop(place);
            }
        }

        @Override
        public void releaseAll(Integer owner) {
            for (Integer p : holds.releaseAll(owner)) {
                drop(p);
            }
        }

        @Override
        public void lease(Integer place, long until) {
            if (holds.lease(place, until)) {
                engine.take(place);
            }
        }

        @Override
        public void expire(long now) {
            for (Integer p : holds.expire(now)) {
                drop(p);
            }
        }

        @Override
        public void renew() {
            for (Integer p : holds.held()) {
                engine.take(p);
            }
        }
    }
}
