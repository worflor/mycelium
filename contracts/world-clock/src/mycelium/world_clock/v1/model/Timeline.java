package mycelium.world_clock.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Meaning laid over a clock (26.x's {@code Timeline}, less its environment-attribute tracks): an optional period and
 * named markers. With a period the timeline repeats (a day, a moon cycle, a mod's Watch or Great Year) and counts its
 * periods; without one it is the clock's total ticks. Several timelines may share a clock.
 */
public final class Timeline {
    private final String id;
    private final ClockId clock;
    private final OptionalInt periodTicks;
    private final Map<String, Marker> markers;

    private Timeline(String id, ClockId clock, OptionalInt periodTicks, Map<String, Marker> markers) {
        this.id = id;
        this.clock = clock;
        this.periodTicks = periodTicks;
        this.markers = markers;
    }

    /** A timeline on {@code clock} of {@code periodTicks} ticks (zero or less: no period); add markers, then build. */
    public static Builder on(ClockId clock, String id, int periodTicks) {
        if (clock == null || id == null || id.isEmpty()) {
            throw new IllegalArgumentException("a timeline needs a clock and an id");
        }
        return new Builder(id, clock, periodTicks > 0 ? OptionalInt.of(periodTicks) : OptionalInt.empty());
    }

    public String id() {
        return id;
    }

    public ClockId clock() {
        return clock;
    }

    public OptionalInt periodTicks() {
        return periodTicks;
    }

    public List<Marker> markers() {
        return Collections.unmodifiableList(new ArrayList<>(markers.values()));
    }

    public Optional<Marker> marker(String markerId) {
        return Optional.ofNullable(markers.get(markerId));
    }

    @Override
    public String toString() {
        return id + "@" + clock + (periodTicks.isPresent() ? "/" + periodTicks.getAsInt() : "");
    }

    public static final class Builder {
        private final String id;
        private final ClockId clock;
        private final OptionalInt period;
        private final Map<String, Marker> markers = new LinkedHashMap<>();

        private Builder(String id, ClockId clock, OptionalInt period) {
            this.id = id;
            this.clock = clock;
            this.period = period;
        }

        /** A marker that shows in commands. Ids are unique in a timeline; a periodic marker lies inside the period. */
        public Builder marker(String markerId, int ticks) {
            return marker(markerId, ticks, true);
        }

        public Builder marker(String markerId, int ticks, boolean showInCommands) {
            if (markers.containsKey(markerId)) {
                throw new IllegalArgumentException("timeline " + id + " already has a marker " + markerId);
            }
            markers.put(markerId, new Marker(markerId, clock, ticks, period, showInCommands));
            return this;
        }

        public Timeline build() {
            return new Timeline(id, clock, period, new LinkedHashMap<>(markers));
        }
    }
}
