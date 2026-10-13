package mycelium.bridges.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the game is called, where this variant touches it: a player's server and the overworld (whose saved data holds the world's forms).
 * These are the only places the version shows through outside the saved data, so they are what an overlay replaces.
 *
 * <p>This is for Minecraft 26.3 on, where a player is reached through its level and not asked for its server.
 */
final class Game {
    private Game() {
    }

    static MinecraftServer server(ServerPlayer player) {
        return player.level().getServer();
    }

    static ServerLevel overworld(MinecraftServer server) {
        return server.overworld();
    }
}
