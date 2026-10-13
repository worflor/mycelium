package mycelium.bridges.lifecycle;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;

/**
 * Forge's events as the lifecycle bridge's crossings (Forge 52 on Minecraft 1.21 to 1.21.1). Registered once, by the first
 * {@link PlayerLifecycle#install()}, on the Forge event bus; each calls the {@link Hooks} method of the crossing it is.
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
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ServerStartedEvent e) -> Hooks.serverStarted(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.login(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.logout(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (PlayerEvent.Clone e) -> {
            if (e.getOriginal() instanceof ServerPlayer old && e.getEntity() instanceof ServerPlayer fresh) {
                Hooks.respawn(old, fresh);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.death(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                Hooks.dimensionChange(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Save e) -> {
            if (e.getLevel() instanceof ServerLevel level && level == level.getServer().overworld()) {
                Hooks.save(level.getServer());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ServerStoppingEvent e) -> Hooks.serverStopping(e.getServer()));
    }
}
