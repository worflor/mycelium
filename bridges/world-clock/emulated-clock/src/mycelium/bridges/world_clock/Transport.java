package mycelium.bridges.world_clock;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The one hook this variant asks of a mod that adds clocks of its own: how to get a small payload to clients. A loader
 * registers its own packets its own way (and it differs by version), so the bridge produces and reads the bytes
 * ({@link ClockWire}) and the mod delivers them:
 *
 * <pre>
 * // server: deliver the bytes with whatever your loader sends a custom payload with
 * Transport transport = new Transport() { ... toAll(server, bytes) -&gt; broadcast; toPlayer(player, bytes) -&gt; send ... };
 * // client: on receipt, hand the bytes back
 * WorldClocks.Client.receive(bytes);
 * </pre>
 *
 * <p>The vanilla overworld clock needs no transport (the game syncs its day time), and a mod that reads no clock of its own
 * on a client can pass {@link #NONE}. The 26.1 and later variant does not use a transport (the game syncs every clock); the
 * type is the same there so a mod's code is the same on every version.
 */
public interface Transport {
    /** Every player on the server. */
    void toAll(MinecraftServer server, byte[] payload);

    /** One player, on joining. */
    void toPlayer(ServerPlayer player, byte[] payload);

    /** No transport: no clock of the mod's is read on a client. */
    Transport NONE = new Transport() {
        @Override
        public void toAll(MinecraftServer server, byte[] payload) {
        }

        @Override
        public void toPlayer(ServerPlayer player, byte[] payload) {
        }
    };
}
