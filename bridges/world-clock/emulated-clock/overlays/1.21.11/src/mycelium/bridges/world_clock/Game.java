package mycelium.bridges.world_clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * What the game before 26.1 is called, where this variant touches it: the overworld, its one {@code dayTime}, the daylight
 * rule, and whether the server runs its tick normally. These are the only places that change with the version, so they are
 * what an overlay replaces; everything else in the variant is the same on every version it covers.
 *
 * <p>This is for Minecraft 1.21.11, where the rules became registry-backed ({@code GameRules.ADVANCE_TIME}) and are read from a level.
 */
final class Game {
    private Game() {
    }

    static ServerLevel overworld(MinecraftServer server) {
        return server.overworld();
    }

    static long gameTime(MinecraftServer server) {
        return overworld(server).getGameTime();
    }

    /** The overworld's day time, which the game before 26.1 shares between every dimension. */
    static long dayTime(MinecraftServer server) {
        return overworld(server).getDayTime();
    }

    static void setDayTime(MinecraftServer server, long ticks) {
        overworld(server).setDayTime(ticks);
    }

    /** The rule that lets day time pass ({@code advance_time}, once called {@code doDaylightCycle}). */
    static boolean advanceTime(MinecraftServer server) {
        return server.overworld().getGameRules().get(GameRules.ADVANCE_TIME);
    }

    static void setAdvanceTime(MinecraftServer server, boolean on) {
        server.overworld().getGameRules().set(GameRules.ADVANCE_TIME, on, server);
    }

    /** Whether the server runs its tick normally (not frozen, not stepping): the game does not pass time otherwise. */
    static boolean runsNormally(MinecraftServer server) {
        return server.tickRateManager().runsNormally();
    }
}
