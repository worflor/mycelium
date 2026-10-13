package mycelium.world_clock.v1.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** What the server tells clients (26.x's {@code ClientboundSetTimePacket}): the game time it was true at, and clocks' network states. */
public record ClockSync(long gameTime, Map<ClockId, NetworkState> clocks) {
    public ClockSync {
        clocks = Collections.unmodifiableMap(new LinkedHashMap<>(clocks));
    }
}
