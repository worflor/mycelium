package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * {@code Game} for Minecraft 26.1 and 26.2, where the clock manager exposes a clock's state only as a packed copy
 * ({@code packState}), reports a move as a flag, and a client can read only a clock's total ticks. The three outcomes of a
 * move are told apart here from the markers the game's timelines register; what a client cannot read (the fraction and the
 * rate) it is given as zero and one, and a mod that needs them on a client should send them.
 */
final class Game {
    private Game() {
    }

    static ClockState state(MinecraftServer server, Holder<WorldClock> clock) {
        net.minecraft.world.clock.ClockState packed = server.clockManager().packState().clocks().get(clock);
        if (packed == null) {
            throw new IllegalArgumentException("the game holds no state for clock " + clock.getRegisteredName());
        }
        try {
            return new ClockState(packed.totalTicks(), packed.partialTick(), packed.rate(), packed.paused());
        } catch (IllegalArgumentException outsideTheModel) {
            throw new IllegalStateException("the game's clock " + clock.getRegisteredName() + " is in a state the model does not hold ("
                    + packed.totalTicks() + ", " + packed.partialTick() + ", rate " + packed.rate() + "): " + outsideTheModel.getMessage(),
                    outsideTheModel);
        }
    }

    static MoveResult move(MinecraftServer server, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker) {
        boolean known = knows(server, clock, marker);
        boolean moved = server.clockManager().moveToTimeMarker(clock, marker);
        return moved ? MoveResult.MOVED : known ? MoveResult.NOT_MOVED : MoveResult.NO_SUCH_MARKER;
    }

    private static boolean knows(MinecraftServer server, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker) {
        boolean[] found = {false};
        server.registryAccess().lookupOrThrow(Registries.TIMELINE).listElements().forEach(timeline ->
                timeline.value().registerTimeMarkers((key, info) -> {
                    if (key.equals(marker) && info.clock().equals(clock)) {
                        found[0] = true;
                    }
                }));
        return found[0];
    }

    static NetworkState network(MinecraftServer server, Holder<WorldClock> clock) {
        ClockNetworkState packed = server.clockManager().createFullSyncPacket().clockUpdates().get(clock);
        if (packed == null) {
            throw new IllegalArgumentException("the game syncs no state for clock " + clock.getRegisteredName());
        }
        return new NetworkState(packed.totalTicks(), packed.partialTick(), packed.rate());
    }

    static boolean advanceTime(MinecraftServer server) {
        return server.getGlobalGameRules().get(GameRules.ADVANCE_TIME);
    }

    static NetworkState clientRead(Level level, Holder<WorldClock> clock) {
        return new NetworkState(level.clockManager().getTotalTicks(clock), 0.0F, 1.0F);
    }
}
