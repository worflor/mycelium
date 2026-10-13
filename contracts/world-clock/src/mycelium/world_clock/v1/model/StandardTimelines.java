package mycelium.world_clock.v1.model;

/**
 * The timelines 26.3 ships on the overworld clock, and so the one clock the game before 26.1 had. They are what
 * {@code world.clock@1} says the old game is (a clock at rate one, a 24000-tick day, a 192000-tick moon), and what a mod
 * lays its own timelines beside. Read from 26.3's {@code data/minecraft/timeline/day.json} and {@code moon.json}.
 */
public final class StandardTimelines {
    public static final ClockId OVERWORLD = new ClockId("minecraft:overworld");
    public static final ClockId THE_END = new ClockId("minecraft:the_end");

    public static final int DAY_TICKS = 24000;
    public static final int MOON_TICKS = 192000;
    public static final int MOON_PHASES = 8;

    /** The marker sleeping moves the dimension's default clock to. */
    public static final String WAKE_UP_FROM_SLEEP = "minecraft:wake_up_from_sleep";

    private StandardTimelines() {
    }

    /** {@code minecraft:day}: 24000 ticks; day 1000, noon 6000, night 13000, midnight 18000, siege roll 18000, wake at 0. */
    public static Timeline day() {
        return Timeline.on(OVERWORLD, "minecraft:day", DAY_TICKS)
                .marker("minecraft:day", 1000)
                .marker("minecraft:noon", 6000)
                .marker("minecraft:night", 13000)
                .marker("minecraft:midnight", 18000)
                .marker("minecraft:roll_village_siege", 18000, false)
                .marker(WAKE_UP_FROM_SLEEP, 0, false)
                .build();
    }

    /** {@code minecraft:moon}: 192000 ticks, eight phases of 24000 starting at the full moon. No markers. */
    public static Timeline moon() {
        return Timeline.on(OVERWORLD, "minecraft:moon", MOON_TICKS).build();
    }
}
