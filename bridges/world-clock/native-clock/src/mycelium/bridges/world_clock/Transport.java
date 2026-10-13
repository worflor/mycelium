package mycelium.bridges.world_clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one hook the emulated variant asks of a mod that adds clocks of its own: how to get a small payload to clients. A
 * loader registers its own packets its own way (and it differs by version), so the bridge produces and reads the bytes
 * ({@code ClockWire}) and the mod delivers them.
 *
 * <p>On 26.1 and later the game syncs every clock itself, so this variant never calls a transport; the type is here so a
 * mod's code is the same on every version. Pass {@link #NONE} when nothing needs it.
 */
public interface Transport {
    /** Every player on the server. */
    void toAll(MinecraftServer server, byte[] payload);

    /** One player, on joining. */
    void toPlayer(ServerPlayer player, byte[] payload);

    /** No transport: the game syncs its clocks, or no clock of the mod's is read on a client. */
    Transport NONE = new Transport() {
        @Override
        public void toAll(MinecraftServer server, byte[] payload) {
        }

        @Override
        public void toPlayer(ServerPlayer player, byte[] payload) {
        }
    };
}
