package mycelium.bridges.lifecycle;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Fabric's events as the lifecycle bridge's crossings (Fabric API on Minecraft 26.3 on). Registered once, by the first
 * {@link PlayerLifecycle#install()}; each calls the {@link Hooks} method of the crossing it is.
 *
 * <ul>
 *   <li>login: {@code ServerPlayConnectionEvents.JOIN}, in a phase before the default one, so a mod's own join handler finds the player
 *       current. It fires when the player has been placed in the world, whoever placed it.</li>
 *   <li>logout: {@code ServerPlayerEvents.LEAVE}, in a phase after the default one: the game removes a player from its list on a
 *       disconnect, a kick and a stop alike, and a mod's own {@code DISCONNECT} handler (which runs before the player is removed) still
 *       finds the player current.</li>
 *   <li>respawn: {@code ServerPlayerEvents.COPY_FROM}, the game copying what a player keeps into the object that replaces it (after a death, or
 *       after the End), before a mod's own copy handler.</li>
 *   <li>death: {@code ServerLivingEntityEvents.AFTER_DEATH}, once the death has gone ahead.</li>
 *   <li>dimension change: {@code ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL} (called {@code ...WorldChangeEvents} before 26.x).</li>
 *   <li>save: {@code ServerLifecycleEvents.BEFORE_SAVE}; the server's start and stop: {@code SERVER_STARTED} and {@code SERVER_STOPPING}
 *       (the stop in a phase after the default one, so a mod's own stop handler runs while its slots are still held).</li>
 * </ul>
 */
public final class LoaderEvents {
    private LoaderEvents() {
    }

    /** Registers the listeners. Called once, by name, from {@link Hooks}. */
    public static void wire() {
        String own = Hooks.class.getPackageName().replace('.', '_');
        Identifier early = Identifier.fromNamespaceAndPath("mycelium", own + "_early");
        Identifier late = Identifier.fromNamespaceAndPath("mycelium", own + "_late");

        ServerLifecycleEvents.SERVER_STARTED.register(Hooks::serverStarted);

        ServerPlayConnectionEvents.JOIN.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        ServerPlayConnectionEvents.JOIN.register(early, (handler, sender, server) -> Hooks.login(handler.getPlayer()));

        ServerPlayerEvents.LEAVE.addPhaseOrdering(Event.DEFAULT_PHASE, late);
        ServerPlayerEvents.LEAVE.register(late, Hooks::logout);

        ServerPlayerEvents.COPY_FROM.addPhaseOrdering(early, Event.DEFAULT_PHASE);
        ServerPlayerEvents.COPY_FROM.register(early, (old, fresh, alive) -> Hooks.respawn(old, fresh));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                Hooks.death(player);
            }
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> Hooks.dimensionChange(player));

        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) -> Hooks.save(server));

        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, late);
        ServerLifecycleEvents.SERVER_STOPPING.register(late, Hooks::serverStopping);
    }
}
