package mycelium.state_lifecycle.v1.model;

import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The port a platform's lifecycle satisfies: what a mod holds once a bridge (Mycelium's {@code lifecycle}) has turned the
 * loader's events into crossings. {@code P} is the platform's player object and {@code S} its server, which this model
 * never names.
 *
 * <p>The bridge is given the mod's {@link SlotRegistry} (its slots and its own transitions) and returns this. From then on the
 * platform performs every crossing the platform has for a player (login, logout, death, respawn, dimension change, save) and
 * for the server (start, save, stop), by {@link Crossings#plan} and {@link Crossings#apply} in a {@link Tx} each, as section 4 of
 * the SPEC describes. The mod:
 *
 * <ul>
 *   <li>asks who is current: {@link #current(Object)} gives a player object's {@link HostRef} while it is the object its host is
 *       current as (and nothing for a player logged out, replaced by a respawn, or never begun);</li>
 *   <li>reads and writes slots through {@link #players()} and {@link #server()}, which are {@link HostStore}s: {@code peek},
 *       {@code read}, {@code materialize} and {@code write} mean exactly what the contract says, and a ref that is not current
 *       answers {@code Gone} (L2);</li>
 *   <li>captures a {@link HostRef}, never the object, in delayed work, and asks {@link HostStore#resolve} when it runs;</li>
 *   <li>fires its own transitions with {@link #fire} or {@link #cross}.</li>
 * </ul>
 *
 * <p>An implementation keeps the laws of the contract on the real game, which the harness cannot run: the bridge's conformance
 * game tests (SPEC section 5) hold it, and hold a mod's override of it, to the cases G1 to G9.
 *
 * @param <P> the platform's player
 * @param <S> the platform's server
 */
public interface LifecyclePort<P, S> {
    /** The registry the bridge drives: the mod's slots, and the transitions they declare policies for. */
    SlotRegistry registry();

    /**
     * Every player in this run of the server: live incarnations, the value cells of each, and the stored form of each durable value.
     * A player is live from login to logout, and a respawn replaces the object with a new incarnation of the same host.
     */
    HostStore<P> players();

    /** The server, one host that lives from its start to its stop. */
    HostStore<S> server();

    /** The id of the player host the platform names by {@code token} (a UUID's text) in the save being played. */
    HostId player(String token);

    /** The id of the server host in the save being played. */
    HostId serverHost();

    /**
     * The player's live incarnation, only while {@code player} is the object that incarnation resolves to. A player object that has
     * logged out, or that a respawn replaced, has none: the object is not the host, and work aimed through it reaches nothing.
     */
    Optional<HostRef> current(P player);

    /** The server's live incarnation, if the server is running. */
    Optional<HostRef> currentServer();

    /**
     * Fires a transition of {@code SAVES} or {@code STAYS} shape on a live host: the mod's own (declared in its
     * {@link Transitions}) or the platform's. A ref that is not current is refused with {@link StaleHostException}; a crossing that
     * fails part way aborts and changes nothing (L8), and its {@link CrossingException} reaches the caller.
     */
    void fire(TransitionId transition, HostRef host);

    /**
     * Fires a transition that creates or ends a player (the mod's own, of shape {@code BEGINS}, {@code ENDS}, {@code SUCCEEDS} or
     * {@code FORKS}), in one transaction. The platform's own transitions are fired by the bridge, not by the mod.
     */
    void cross(TransitionId transition, Crossing<P> crossing);

    // --- access, in a transaction of its own ----------------------------------------------------------------------

    /** The store that keeps hosts of this kind. The bridge keeps players and the server. */
    default HostStore<?> storeFor(HostKind kind) {
        switch (kind) {
            case PLAYER:
                return players();
            case SERVER:
                return server();
            default:
                throw new IllegalArgumentException("the lifecycle bridge keeps players and the server, not " + kind + " hosts");
        }
    }

    /** Sets a slot's value on a live host in a transaction of its own: {@link HostStore#write}, committed. */
    default <T> Reading<T> write(Slot<T> slot, HostRef host, T value) {
        try (Tx tx = Tx.open()) {
            Reading<T> reading = storeFor(slot.scope()).write(slot, host, value, tx);
            tx.commit();
            return reading;
        }
    }

    /** Creates the slot's initial value on a live host if there is none: {@link HostStore#materialize}, committed. */
    default <T> Reading<T> materialize(Slot<T> slot, HostRef host) {
        try (Tx tx = Tx.open()) {
            Reading<T> reading = storeFor(slot.scope()).materialize(slot, host, tx);
            tx.commit();
            return reading;
        }
    }

    /**
     * Replaces a slot's effective value with {@code f} of it (the declared initial value where there is none), in a transaction of its own.
     * Values are immutable, so {@code f} builds a new one; the host being gone answers {@code Gone} and calls {@code f} on nothing.
     */
    default <T> Reading<T> update(Slot<T> slot, HostRef host, UnaryOperator<T> f) {
        Reading<T> now = storeFor(slot.scope()).read(slot, host);
        if (!(now instanceof Reading.Present<T> present)) {
            return now;
        }
        return write(slot, host, f.apply(present.value()));
    }
}
