package mycelium.bridges.lifecycle;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the loader's events call: one method per crossing the platform has, each handed to every installed {@link PlayerLifecycle}.
 * The loader overlay's {@code LoaderEvents} registers the loader's listeners once and has each call the method of the same name here;
 * a mod on a loader the bridge has no half for calls these from its own listeners and gets the same crossings.
 *
 * <p>A listener should run the login before a mod's own login handlers and the logout and the server stop after its own, so that a
 * handler of the mod's reads a player's slots while the player is current. The loader's overlay says how it orders them.
 */
public final class Hooks {
    private static final List<PlayerLifecycle> ACTIVE = new CopyOnWriteArrayList<>();
    private static boolean wired;

    private Hooks() {
    }

    // ---------------------------------------------------------------------------------------------------- installing

    static void install(PlayerLifecycle lifecycle) {
        if (!ACTIVE.contains(lifecycle)) {
            ACTIVE.add(lifecycle);
        }
        synchronized (Hooks.class) {
            if (wired) {
                return;
            }
            wired = true;
        }
        try {
            // the loader's half is the one class a loader overlay adds, so the core reaches it by name, in its own (moved) package
            Class.forName(Hooks.class.getPackageName() + ".LoaderEvents").getMethod("wire").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            synchronized (Hooks.class) {
                wired = false;
            }
            ACTIVE.remove(lifecycle);
            throw new IllegalStateException("the lifecycle bridge has no loader half in this build (mycelium { loader = ... } names the loader whose"
                    + " events it listens to), or it could not be wired: " + e, e);
        }
    }

    static void remove(PlayerLifecycle lifecycle) {
        ACTIVE.remove(lifecycle);
    }

    /** Whether any lifecycle hears the events. */
    public static boolean installed() {
        return !ACTIVE.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- the crossings

    /** The server has started. */
    public static void serverStarted(MinecraftServer server) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onServerStarted(server);
        }
    }

    /** A player has joined the server and is in the world. */
    public static void login(ServerPlayer player) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onLogin(player);
        }
    }

    /** A player is leaving the server: their data has not been saved yet. */
    public static void logout(ServerPlayer player) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onLogout(player);
        }
    }

    /** The game made {@code fresh} from {@code old}, copying what a player keeps (a respawn after death or after the End). */
    public static void respawn(ServerPlayer old, ServerPlayer fresh) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onRespawn(old, fresh);
        }
    }

    /** A player has died (the same object, until it is respawned). */
    public static void death(ServerPlayer player) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onDeath(player);
        }
    }

    /** A player has arrived in another dimension (the same object). */
    public static void dimensionChange(ServerPlayer player) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onDimensionChange(player);
        }
    }

    /** The world is being saved. */
    public static void save(MinecraftServer server) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onSave(server);
        }
    }

    /** The server is stopping: its players are still on it. */
    public static void serverStopping(MinecraftServer server) {
        for (PlayerLifecycle l : ACTIVE) {
            l.onServerStopping(server);
        }
    }
}
