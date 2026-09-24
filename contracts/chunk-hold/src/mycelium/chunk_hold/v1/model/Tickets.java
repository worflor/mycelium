package mycelium.chunk_hold.v1.model;

/**
 * The engine's side of chunk.hold@1: one ticket per place. Taking a place's ticket when it has one keeps one (and starts
 * its timeout over); dropping it drops it, whoever took it. Vanilla 26.3's {@code TicketStorage} matches tickets on type
 * and level alone; 1.21.1's {@code DistanceManager} on type, level and key. Either way, no holder is counted.
 */
public interface Tickets<K> {
    void take(K place);

    void drop(K place);
}
