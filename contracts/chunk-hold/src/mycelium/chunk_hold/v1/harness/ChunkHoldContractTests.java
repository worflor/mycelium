package mycelium.chunk_hold.v1.harness;

import mycelium.chunk_hold.v1.model.DedupedTickets;
import mycelium.chunk_hold.v1.model.Keeper;
import mycelium.chunk_hold.v1.model.Tickets;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** chunk.hold@1: the laws of keeping places held for several holders over an engine that does not count them (SPEC.md). */
public final class ChunkHoldContractTests {
    private final Function<Tickets<Integer>, Keeper<Integer, Integer>> keepers;

    public ChunkHoldContractTests(Function<Tickets<Integer>, Keeper<Integer, Integer>> keepers) {
        this.keepers = keepers;
    }

    public Check run() {
        Check c = new Check();
        h1ExactlyWhileHeld(c);
        h2OnlyYourOwn(c);
        h3StrangerDropsNothing(c);
        h4ReleaseAllExactly(c);
        h5LeasesLapseOnTime(c);
        h6RenewTakesEveryHeld(c);
        return c;
    }

    /**
     * H1: three holders and leases taking and letting go of four places at random (a fixed seed, 4000 steps): after
     * every call, a place has the engine's ticket exactly while something holds it.
     */
    private void h1ExactlyWhileHeld(Check c) {
        Seeded r = new Seeded(0xC40CL);
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        Map<Integer, Set<Integer>> owners = new HashMap<>();
        Map<Integer, Long> leases = new HashMap<>();
        long now = 0;
        boolean exact = true;
        for (int step = 0; step < 4000 && exact; step++) {
            int place = r.nextInt(4);
            int owner = r.nextInt(3);
            switch (r.nextInt(5)) {
                case 0 -> {
                    k.hold(place, owner);
                    owners.computeIfAbsent(place, x -> new HashSet<>()).add(owner);
                }
                case 1 -> {
                    k.release(place, owner);
                    owners.getOrDefault(place, new HashSet<>()).remove(owner);
                }
                case 2 -> {
                    k.releaseAll(owner);
                    owners.values().forEach(s -> s.remove(owner));
                }
                case 3 -> {
                    long until = now + r.nextInt(50);
                    k.lease(place, until);
                    leases.merge(place, until, Math::max);
                }
                default -> {
                    now += r.nextInt(20);
                    k.expire(now);
                    long t = now;
                    leases.values().removeIf(u -> u <= t);
                }
            }
            for (int p = 0; p < 4; p++) {
                boolean held = !owners.getOrDefault(p, Set.of()).isEmpty() || leases.containsKey(p);
                exact &= held == engine.ticketed(p);
            }
        }
        c.that(exact, "H1:ticketed-exactly-while-held");
    }

    /** H2: two owners hold a place; one lets go, and the place keeps its ticket; the other lets go, and it drops. */
    private void h2OnlyYourOwn(Check c) {
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        k.hold(7, 1);
        k.hold(7, 2);
        k.release(7, 1);
        c.that(engine.ticketed(7), "H2:release-drops-only-your-own");
        k.release(7, 2);
        c.that(!engine.ticketed(7), "H2:the-last-one-drops-it");
    }

    /** H3: letting go of what you do not hold drops nothing. */
    private void h3StrangerDropsNothing(Check c) {
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        k.hold(3, 1);
        k.release(3, 2);
        k.release(4, 1);
        c.that(engine.ticketed(3), "H3:stranger-drops-nothing");
        k.lease(5, 100);
        k.release(5, 1);
        c.that(engine.ticketed(5), "H3:nobody-lets-go-of-a-lease");
    }

    /** H4: an owner letting go of everything drops exactly the places only it held. */
    private void h4ReleaseAllExactly(Check c) {
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        k.hold(1, 1);
        k.hold(2, 1);
        k.hold(2, 2);
        k.lease(3, 50);
        k.hold(3, 1);
        k.releaseAll(1);
        c.that(!engine.ticketed(1) && engine.ticketed(2) && engine.ticketed(3), "H4:release-all-exactly");
    }

    /** H5: a lease holds until its time and lapses then, by itself; asked for again it is extended, never shortened. */
    private void h5LeasesLapseOnTime(Check c) {
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        k.lease(9, 100);
        k.expire(99);
        boolean held = engine.ticketed(9);
        k.lease(9, 200);
        k.lease(9, 150);
        k.expire(199);
        boolean extended = engine.ticketed(9);
        k.expire(200);
        boolean lapsed = !engine.ticketed(9);
        c.that(held && extended && lapsed, "H5:lease-lapses-on-time");
    }

    /** H6: renewing takes every held place's ticket again, owned or leased, and nothing else. */
    private void h6RenewTakesEveryHeld(Check c) {
        DedupedTickets<Integer> engine = new DedupedTickets<>();
        Keeper<Integer, Integer> k = keepers.apply(engine);
        k.hold(1, 1);
        k.lease(2, 100);
        k.hold(3, 1);
        k.release(3, 1);
        int before = engine.takes();
        k.renew();
        c.equal(2, engine.takes() - before, "H6:renew-takes-every-held");
    }
}
