package mycelium.world_clock.v1.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A client's view of clocks, as 26.x's {@code ClientClockManager} keeps it: the states last synced, advanced by their
 * rate as game time passes. Receiving a sync first advances every clock to the sync's game time, then replaces the clocks
 * it names. A clock never heard of reads tick zero at rate one.
 *
 * <p>A native client does this itself; this is for an emulated one, and for the laws.
 */
public final class ClientClocks {
    private static final NetworkState UNKNOWN = new NetworkState(0L, 0.0F, 1.0F);

    private final ClockModel model;
    private final Map<ClockId, NetworkState> clocks = new LinkedHashMap<>();
    private long lastTickGameTime;

    public ClientClocks(ClockModel model) {
        this.model = model;
    }

    /** The client reached this game time: every clock advances by the ticks since the last call. */
    public void tick(long gameTime) {
        long elapsed = gameTime - lastTickGameTime;
        lastTickGameTime = gameTime;
        for (Map.Entry<ClockId, NetworkState> e : clocks.entrySet()) {
            e.setValue(model.extrapolate(e.getValue(), elapsed));
        }
    }

    /** A sync arrived (on join, or when a clock changed). */
    public void handle(ClockSync sync) {
        tick(sync.gameTime());
        clocks.putAll(sync.clocks());
    }

    public NetworkState read(ClockId clock) {
        NetworkState s = clocks.get(clock);
        return s == null ? UNKNOWN : s;
    }
}
