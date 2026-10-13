package mycelium.bridges.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the game is called, where this variant touches it: a player's server and the overworld (whose saved data holds the world's forms).
 * These are the only places the version shows through outside the saved data, so they are what an overlay replaces.
 *
 * <p>This is the base for Minecraft 1.21 to 1.21.1.
 */
final class Game {
    private Game() {
    }

    static MinecraftServer server(ServerPlayer player) {
        return player.getServer();
    }

    static ServerLevel overworld(MinecraftServer server) {
        return server.overworld();
    }
}
