package mycelium.bridges.world_clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * What the game before 26.1 is called, where this variant touches it: the overworld, its one {@code dayTime}, the daylight
 * rule, and whether the server runs its tick normally. These are the only places that change with the version, so they are
 * what an overlay replaces; everything else in the variant is the same on every version it covers.
 *
 * <p>This is the base for Minecraft 1.20.5 to 1.21.4.
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

    /** The rule that lets day time pass ({@code doDaylightCycle}). */
    static boolean advanceTime(MinecraftServer server) {
        return server.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    static void setAdvanceTime(MinecraftServer server, boolean on) {
        server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(on, server);
    }

    /** Whether the server runs its tick normally (not frozen, not stepping): the game does not pass time otherwise. */
    static boolean runsNormally(MinecraftServer server) {
        return server.tickRateManager().runsNormally();
    }
}
