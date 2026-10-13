package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The saved form of a mod's clocks: one line per clock, {@code id total partialBits rateBits paused}, with the float bits in
 * hexadecimal so a fraction survives a save exactly (a decimal rendering would not). Plain text keeps the saved data one
 * string on every version, whatever the game's tag classes are called there. A line that cannot be read is skipped, never
 * guessed at: that clock starts again from tick zero, as a new one does.
 */
final class ClockText {
    private ClockText() {
    }

    static String write(Map<ClockId, ClockState> clocks) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<ClockId, ClockState> e : clocks.entrySet()) {
            ClockState s = e.getValue();
            sb.append(e.getKey().id()).append(' ').append(s.totalTicks()).append(' ')
                    .append(Integer.toHexString(Float.floatToIntBits(s.partialTick()))).append(' ')
                    .append(Integer.toHexString(Float.floatToIntBits(s.rate()))).append(' ')
                    .append(s.paused() ? '1' : '0').append('\n');
        }
        return sb.toString();
    }

    static Map<ClockId, ClockState> read(String text) {
        Map<ClockId, ClockState> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] p = line.trim().split(" ");
            if (p.length != 5) {
                continue;
            }
            try {
                out.put(new ClockId(p[0]), new ClockState(Long.parseLong(p[1]), Float.intBitsToFloat((int) Long.parseLong(p[2], 16)),
                        Float.intBitsToFloat((int) Long.parseLong(p[3], 16)), p[4].equals("1")));
            } catch (IllegalArgumentException unreadable) {
                // skipped: see the class comment
            }
        }
        return out;
    }
}
