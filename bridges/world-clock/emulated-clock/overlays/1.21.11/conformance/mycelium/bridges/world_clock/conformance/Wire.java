package mycelium.bridges.world_clock.conformance;

import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.NetworkState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * What the conformance cases need of the game that differs by variant (decision 0016): the game's
 * rule and its own reading of the overworld's day time, and what a client is handed. This is the {@code emulated-clock}
 * variant's (Minecraft before 26.1): the game has one {@code dayTime}, and a client is handed the port's sync. The daylight rule is
 * read the way Minecraft 1.21.11 names it (registry-backed, read from a level).
 */
final class Wire {
    private Wire() {
    }

    // ---------------------------------------------------------------------------------------------------- the game's own

    static boolean ruleOn(ServerLevel level) {
        return level.getServer().overworld().getGameRules().get(GameRules.ADVANCE_TIME);
    }

    static void setRule(ServerLevel level, boolean on) {
        level.getServer().overworld().getGameRules().set(GameRules.ADVANCE_TIME, on, level.getServer());
    }

    /** The overworld's day time as the game itself reads it (the level's, not the bridge's), never below zero. */
    static long gameOverworld(ServerLevel level) {
        return Math.max(0L, level.getServer().overworld().getDayTime());
    }

    static void gameSetOverworld(ServerLevel level, long ticks) {
        level.getServer().overworld().setDayTime(ticks);
    }

    /** The overworld is vanilla's day time: rate one, not for a mod to change. */
    static boolean overworldRateFixed() {
        return true;
    }

    /** The port holds its own clocks and the game moves the overworld itself, so stepping the port moves nothing else. */
    static void isolate(ClockPort port, ClockId keep, Runnable body) {
        body.run();
    }

    /** The game before 26.1 has no marker code to ask. */
    static Boolean gameAtMarker(ServerLevel level, ClockId clock, String markerId) {
        return null;
    }

    /** The game before 26.1 has no timelines to ask. */
    static long[] gamePeriods(ServerLevel level, String timelineId) {
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- clients

    /** What a client is handed now: the port's sync. */
    static ClockSync delivered(ServerLevel level, ClockPort port, boolean rule) {
        return port.sync(rule);
    }

    /** The game's own client formula over a delivered sync: there is none before 26.1, the bridge's client is the model's. */
    interface RealClient {
        NetworkState read(ClockId clock, long gameTime);
    }

    static RealClient realClient(ServerLevel level, ClockSync delivered) {
        return null;
    }
}
