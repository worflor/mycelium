package mycelium.bridges.world_clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * What the game before 26.1 is called, where this variant touches it: the overworld, its one {@code dayTime}, the daylight
 * rule, and whether the server runs its tick normally. These are the only places that change with the version, so they are
 * what an overlay replaces; everything else in the variant is the same on every version it covers.
 *
 * <p>This is for Minecraft 1.14 and 1.15, which have no tick rate manager and find the overworld through the dimension type.
 */
final class Game {
    private Game() {
    }

    static ServerLevel overworld(MinecraftServer server) {
        return server.getLevel(DimensionType.OVERWORLD);
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

    /** Whether the server runs its tick normally: before 1.20.3 there is no tick rate manager, so it always does. */
    static boolean runsNormally(MinecraftServer server) {
        return true;
    }
}
