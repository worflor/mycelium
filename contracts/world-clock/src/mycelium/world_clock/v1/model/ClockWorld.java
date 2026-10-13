package mycelium.world_clock.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a world declares about time: its clocks, the timelines laid on them, and which clock each dimension runs by
 * default (26.x's {@code default_clock} on a dimension type; a dimension with none, like the Nether, has no time of its
 * own). This is the declaration a mod writes once and a bridge realises: as datapack JSON on 26.1 and later, as saved data
 * on the overworld before.
 *
 * <p>A marker id names one marker on a clock. The game keeps one map of markers per clock and does not check, so two
 * timelines on a clock that reuse an id make a command's meaning depend on registration order; a world here refuses it.
 */
public final class ClockWorld {
    private final Set<ClockId> clocks;
    private final List<Timeline> timelines;
    private final Map<String, ClockId> defaultClocks;

    public ClockWorld(Set<ClockId> clocks, List<Timeline> timelines, Map<String, ClockId> defaultClocks) {
        this.clocks = Collections.unmodifiableSet(new LinkedHashSet<>(clocks));
        this.timelines = Collections.unmodifiableList(new ArrayList<>(timelines));
        this.defaultClocks = Collections.unmodifiableMap(new LinkedHashMap<>(defaultClocks));
        Map<ClockId, Set<String>> seen = new LinkedHashMap<>();
        for (Timeline t : this.timelines) {
            if (!this.clocks.contains(t.clock())) {
                throw new IllegalArgumentException("timeline " + t + " is on a clock the world does not declare");
            }
            Set<String> ids = seen.computeIfAbsent(t.clock(), c -> new LinkedHashSet<>());
            for (Marker m : t.markers()) {
                if (!ids.add(m.id())) {
                    throw new IllegalArgumentException("marker " + m.id() + " is declared twice on clock " + t.clock());
                }
            }
        }
        for (Map.Entry<String, ClockId> e : this.defaultClocks.entrySet()) {
            if (!this.clocks.contains(e.getValue())) {
                throw new IllegalArgumentException("dimension " + e.getKey() + " defaults to an undeclared clock " + e.getValue());
            }
        }
    }

    /** The vanilla world: the overworld and end clocks, the day and moon timelines, the Nether with no clock. */
    public static ClockWorld vanilla() {
        Set<ClockId> clocks = new LinkedHashSet<>();
        clocks.add(StandardTimelines.OVERWORLD);
        clocks.add(StandardTimelines.THE_END);
        List<Timeline> timelines = new ArrayList<>();
        timelines.add(StandardTimelines.day());
        timelines.add(StandardTimelines.moon());
        Map<String, ClockId> defaults = new LinkedHashMap<>();
        defaults.put("minecraft:overworld", StandardTimelines.OVERWORLD);
        defaults.put("minecraft:the_end", StandardTimelines.THE_END);
        return new ClockWorld(clocks, timelines, defaults);
    }

    public Set<ClockId> clocks() {
        return clocks;
    }

    public List<Timeline> timelines() {
        return timelines;
    }

    public List<Timeline> timelinesOn(ClockId clock) {
        List<Timeline> out = new ArrayList<>();
        for (Timeline t : timelines) {
            if (t.clock().equals(clock)) {
                out.add(t);
            }
        }
        return out;
    }

    /** A clock's marker by id, whichever of its timelines declares it. */
    public Optional<Marker> marker(ClockId clock, String markerId) {
        for (Timeline t : timelines) {
            if (t.clock().equals(clock)) {
                Optional<Marker> m = t.marker(markerId);
                if (m.isPresent()) {
                    return m;
                }
            }
        }
        return Optional.empty();
    }

    /** The clock a dimension runs by default, if it has one. */
    public Optional<ClockId> defaultClock(String dimension) {
        return Optional.ofNullable(defaultClocks.get(dimension));
    }

    public Map<String, ClockId> defaultClocks() {
        return defaultClocks;
    }

    /** This world with one more timeline, and its clock declared if it was not. */
    public ClockWorld with(Timeline timeline) {
        Set<ClockId> cs = new LinkedHashSet<>(clocks);
        cs.add(timeline.clock());
        List<Timeline> ts = new ArrayList<>(timelines);
        ts.add(timeline);
        return new ClockWorld(cs, ts, defaultClocks);
    }

    /** This world with a dimension defaulting to a clock, declared if it was not. */
    public ClockWorld withDefault(String dimension, ClockId clock) {
        Set<ClockId> cs = new LinkedHashSet<>(clocks);
        cs.add(clock);
        Map<String, ClockId> ds = new LinkedHashMap<>(defaultClocks);
        ds.put(dimension, clock);
        return new ClockWorld(cs, timelines, ds);
    }
}
