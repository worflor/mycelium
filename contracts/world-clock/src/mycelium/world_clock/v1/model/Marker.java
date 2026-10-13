package mycelium.world_clock.v1.model;

import java.util.OptionalInt;

/**
 * A named point in a timeline (26.x's {@code ClockTimeMarker} with its key): {@code ticks} into the timeline's period,
 * or, with no period, the absolute tick. A marker takes its period from its timeline ({@link Timeline#on}), so every
 * marker of a periodic timeline repeats with it. What a marker means (when it occurs, where it moves a clock) is the
 * {@link ClockModel}'s, so a wrong meaning can be tried against the laws.
 */
public record Marker(String id, ClockId clock, int ticks, OptionalInt periodTicks, boolean showInCommands) {
    public Marker {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a marker needs an id");
        }
        if (clock == null || periodTicks == null) {
            throw new IllegalArgumentException("clock and period are required");
        }
        if (ticks < 0) {
            throw new IllegalArgumentException("a marker's ticks cannot be negative: " + ticks);
        }
        if (periodTicks.isPresent() && (periodTicks.getAsInt() <= 0 || ticks >= periodTicks.getAsInt())) {
            throw new IllegalArgumentException("marker " + id + " at " + ticks + " does not fall inside a period of "
                    + periodTicks.getAsInt() + " (26.x refuses it)");
        }
    }

    public boolean periodic() {
        return periodTicks.isPresent();
    }
}
