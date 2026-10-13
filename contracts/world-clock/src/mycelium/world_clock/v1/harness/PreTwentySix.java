package mycelium.world_clock.v1.harness;

/**
 * The game before 26.1 as arithmetic: one {@code dayTime}, read from 1.21.1's bytecode (and the same in 1.20.1), written
 * here as the code those methods compile from. This is the oracle law C4 holds the model to; it is not part of the
 * contract's model, and nothing in the model refers to it.
 *
 * <ul>
 *   <li>{@code ServerLevel.tickTime}: {@code gameTime + 1}, and {@code if (doDaylightCycle) setDayTime(getDayTime() + 1)}.</li>
 *   <li>{@code ServerLevel.tick}, enough players asleep: {@code if (doDaylightCycle) { long l = getDayTime() + 24000L;
 *       setDayTime(l - l % 24000L); }}.</li>
 *   <li>{@code DimensionType.moonPhase(long t)}: {@code (int) (t / 24000L % 8L + 8L) % 8}.</li>
 *   <li>{@code TimeCommand.getDayTime}: {@code (int) (dayTime % 24000L)}; the day count is {@code dayTime / 24000L}.</li>
 * </ul>
 */
final class PreTwentySix {
    private PreTwentySix() {
    }

    static long tickTime(long dayTime, boolean doDaylightCycle) {
        return doDaylightCycle ? dayTime + 1L : dayTime;
    }

    static long sleepSkip(long dayTime, boolean doDaylightCycle) {
        if (!doDaylightCycle) {
            return dayTime;
        }
        long l = dayTime + 24000L;
        return l - l % 24000L;
    }

    static int moonPhase(long t) {
        return (int) (t / 24000L % 8L + 8L) % 8;
    }

    static long dayTimeOfDay(long dayTime) {
        return dayTime % 24000L;
    }

    static long dayCount(long dayTime) {
        return dayTime / 24000L;
    }

    /** {@code data.catalog@1}'s reference rule (its SPEC section 5), restated so the dawn example needs no other package. */
    static final class DawnRule {
        private Long baselineDay;

        /** The day entered, or -1 when this sample is not a dawn. */
        long observe(long worldTime) {
            long day = Math.floorDiv(worldTime, 24000L);
            if (baselineDay == null) {
                baselineDay = day;
                return -1L;
            }
            if (day > baselineDay) {
                baselineDay = day;
                return day >= 0 ? day : -1L;
            }
            if (day < baselineDay) {
                baselineDay = day;
            }
            return -1L;
        }
    }
}
