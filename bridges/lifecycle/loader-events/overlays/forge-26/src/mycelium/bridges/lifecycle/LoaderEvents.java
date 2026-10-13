package mycelium.bridges.lifecycle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.listener.Priority;

/**
 * Forge's events as the lifecycle bridge's crossings (Forge 66 on Minecraft 26.3 on). Registered once, by the first
 * {@link PlayerLifecycle#install()}; each calls the {@link Hooks} method of the crossing it is. An event here is a record with a bus of its
 * own, a priority is a byte, and the listener of a cancellable one answers whether to cancel it (these never do).
 *
 * <ul>
 *   <li>login: {@code PlayerLoggedInEvent} at the highest priority, so a mod's own login handler finds the player current;</li>
 *   <li>logout: {@code PlayerLoggedOutEvent} at the lowest, so a mod's own logout handler still finds the player current;</li>
 *   <li>respawn: {@code PlayerEvent.Clone}, the game copying what a player keeps into the object that replaces it (after a death, or
 *       after the End), at the highest priority;</li>
 *   <li>death: {@code LivingDeathEvent} as the death begins (a cancelled death is not heard); dimension change:
 *       {@code PlayerChangedDimensionEvent};</li>
 *   <li>save: {@code LevelEvent.Save} of the overworld; the server's start and stop: {@code ServerStartedEvent} and
 *       {@code ServerStoppingEvent} (the stop at the lowest priority, so a mod's own stop handler runs while its slots are still held).</li>
 * </ul>
 */
public final class LoaderEvents {
    private LoaderEvents() {
    }

    /** Registers the listeners. Called once, by name, from {@link Hooks}. */
    public static void wire() {
        ServerStartedEvent.BUS.addListener(Priority.HIGHEST, (ServerStartedEvent e) -> Hooks.serverStarted(e.getServer()));
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(Priority.HIGHEST, (PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.login(player);
            }
        });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(Priority.LOWEST, (PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.logout(player);
            }
        });
        PlayerEvent.Clone.BUS.addListener(Priority.HIGHEST, (PlayerEvent.Clone e) -> {
            if (e.getOriginal() instanceof ServerPlayer old && e.getEntity() instanceof ServerPlayer fresh) {
                Hooks.respawn(old, fresh);
            }
        });
        LivingDeathEvent.BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.death(player);
            }
            return false;
        });
        PlayerEvent.PlayerChangedDimensionEvent.BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.dimensionChange(player);
            }
        });
        LevelEvent.Save.BUS.addListener((LevelEvent.Save e) -> {
            if (e.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) {
                Hooks.save(level.getServer());
            }
        });
        ServerStoppingEvent.BUS.addListener(Priority.LOWEST, (ServerStoppingEvent e) -> Hooks.serverStopping(e.getServer()));
    }
}
