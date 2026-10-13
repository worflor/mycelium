package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Timeline;

import java.util.Map;
import java.util.TreeMap;

/**
 * A mod's clocks and timelines as the datapack files 26.1 and later read them: {@code data/<ns>/world_clock/<id>.json} (a
 * clock is an empty object: identity only) and {@code data/<ns>/timeline/<id>.json} (its clock, period and time markers).
 * Pure text, no game: a mod writes the files from its declaration in its data generator or a build step, or checks the files
 * it keeps by hand against {@link #files}.
 *
 * <p>Only the files of one namespace are written, since the game's own clocks and timelines ({@code minecraft:}) are the
 * game's. The model has periods and markers, not keyframe tracks: a timeline written here has none.
 */
public final class ClockDatapack {
    private ClockDatapack() {
    }

    /** Relative file path to file text, for every clock and timeline of {@code world} in {@code namespace}. */
    public static Map<String, String> files(ClockWorld world, String namespace) {
        Map<String, String> out = new TreeMap<>();
        for (ClockId clock : world.clocks()) {
            String[] id = split(clock.id());
            if (id[0].equals(namespace)) {
                out.put("data/" + id[0] + "/world_clock/" + id[1] + ".json", "{}\n");
            }
        }
        for (Timeline timeline : world.timelines()) {
            String[] id = split(timeline.id());
            if (id[0].equals(namespace)) {
                out.put("data/" + id[0] + "/timeline/" + id[1] + ".json", timeline(timeline));
            }
        }
        return out;
    }

    /** One timeline as the game's JSON: stable key order, one marker to a line. */
    public static String timeline(Timeline timeline) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("  \"clock\": \"").append(timeline.clock().id()).append("\"");
        if (timeline.periodTicks().isPresent()) {
            sb.append(",\n  \"period_ticks\": ").append(timeline.periodTicks().getAsInt());
        }
        if (!timeline.markers().isEmpty()) {
            sb.append(",\n  \"time_markers\": {\n");
            boolean first = true;
            for (Marker m : sorted(timeline)) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    \"").append(m.id()).append("\": {\"ticks\": ").append(m.ticks())
                        .append(", \"show_in_commands\": ").append(m.showInCommands()).append("}");
            }
            sb.append("\n  }");
        }
        return sb.append("\n}\n").toString();
    }

    private static Iterable<Marker> sorted(Timeline timeline) {
        Map<String, Marker> byId = new TreeMap<>();
        for (Marker m : timeline.markers()) {
            byId.put(m.id(), m);
        }
        return byId.values();
    }

    private static String[] split(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            throw new IllegalArgumentException("an id is namespace:path, not '" + id + "'");
        }
        return new String[] {id.substring(0, colon), id.substring(colon + 1)};
    }
}
