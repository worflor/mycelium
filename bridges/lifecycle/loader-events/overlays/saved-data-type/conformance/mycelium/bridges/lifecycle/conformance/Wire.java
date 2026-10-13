package mycelium.bridges.lifecycle.conformance;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * How the conformance cases drive the game of this overlay (Minecraft 26.3 on): the server's own paths, so that the loader's event for
 * each fires for real. A player is placed in the world by {@code PlayerList.placeNewPlayer} over a connection that goes nowhere,
 * removed by {@code PlayerList.remove} (what a disconnect, a kick and a stop all do), replaced by {@code PlayerList.respawn} (what a
 * death and the End do), killed by {@code die}, and taken to another dimension by the portal's own {@code teleport}. This is the
 * 1.21 file with the server reached through the player's level and the transition renamed.
 */
public final class Wire {
    private Wire() {
    }

    /** A player the game places in the world, as it places one who connects. */
    public static ServerPlayer join(ServerLevel level, UUID id, String name) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(id, name);
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        ServerPlayer player = new ServerPlayer(server, level, profile, cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    /** A player object the game never placed: nobody's host. */
    public static ServerPlayer stranger(ServerLevel level) {
        return new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "stranger"), ClientInformation.createDefault());
    }

    /** The game removes the player from its list, as it does on a disconnect. */
    public static void leave(ServerPlayer player) {
        player.level().getServer().getPlayerList().remove(player);
    }

    /** Whether the game still has the player in its list. */
    public static boolean online(ServerPlayer player) {
        return player.level().getServer().getPlayerList().getPlayers().contains(player);
    }

    /** The game replaces the player with a new object, copying what a player keeps, as it does after a death. */
    public static ServerPlayer respawn(ServerPlayer player) {
        return player.level().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
    }

    /** The player dies. */
    public static void die(ServerPlayer player) {
        player.die(player.damageSources().generic());
    }

    /** The player is taken to {@code to} as a portal takes one: the same object, in another dimension. */
    public static void travel(ServerPlayer player, ServerLevel to) {
        player.teleport(new TeleportTransition(to, player.position(), Vec3.ZERO, player.getYRot(), player.getXRot(), TeleportTransition.DO_NOTHING));
    }

    /** A dimension other than the overworld the test world has. */
    public static ServerLevel otherDimension(MinecraftServer server) {
        for (net.minecraft.resources.ResourceKey<Level> key : java.util.List.of(Level.NETHER, Level.END)) {
            ServerLevel level = server.getLevel(key);
            if (level != null) {
                return level;
            }
        }
        throw new AssertionError("the test world has no dimension but the overworld, so a dimension change cannot be driven");
    }
}
