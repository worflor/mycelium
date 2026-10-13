package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ClockInstance;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Where this variant touches the clock manager, and so where the manager's shape shows. The manager's public surface moved
 * inside the 26 line (26.1 and 26.2 expose a clock's state only as a packed copy and report a move as a flag; 26.3 gives
 * each clock an instance to read and a move three outcomes), so these few methods are what an overlay replaces. Everything
 * else in the variant is the same on every version it covers.
 *
 * <p>This is the base, for Minecraft 26.3 and later.
 */
final class Game {
    private Game() {
    }

    /** A clock's state as the game holds it. */
    static ClockState state(MinecraftServer server, Holder<WorldClock> clock) {
        ClockInstance instance = server.clockManager().getInstance(clock);
        try {
            return new ClockState(instance.totalTicks(), instance.partialTick(), instance.rate(), instance.isPaused());
        } catch (IllegalArgumentException outsideTheModel) {
            throw new IllegalStateException("the game's clock " + clock.getRegisteredName() + " is in a state the model does not hold ("
                    + instance.totalTicks() + ", " + instance.partialTick() + ", rate " + instance.rate() + "): "
                    + outsideTheModel.getMessage(), outsideTheModel);
        }
    }

    /** What moving a clock to a marker did. */
    static MoveResult move(MinecraftServer server, Holder<WorldClock> clock, ResourceKey<ClockTimeMarker> marker) {
        return switch (server.clockManager().moveToTimeMarker(clock, marker)) {
            case NO_TIME_MARKER_FOUND -> MoveResult.NO_SUCH_MARKER;
            case NOT_MOVED -> MoveResult.NOT_MOVED;
            case MOVED -> MoveResult.MOVED;
        };
    }

    /** What the game tells clients of a clock now. */
    static NetworkState network(MinecraftServer server, Holder<WorldClock> clock) {
        ClockNetworkState packed = server.clockManager().getInstance(clock).packNetworkState(server);
        return new NetworkState(packed.totalTicks(), packed.partialTick(), packed.rate());
    }

    /** The rule that lets clocks advance ({@code advance_time}), which the manager reads for itself. */
    static boolean advanceTime(MinecraftServer server) {
        return server.getGlobalGameRules().get(GameRules.ADVANCE_TIME);
    }

    /** What a client knows of a clock: ticks, fraction, and the rate it runs at (zero when paused). */
    static NetworkState clientRead(Level level, Holder<WorldClock> clock) {
        ClockInstance instance = level.clockManager().getInstance(clock);
        return new NetworkState(instance.totalTicks(), instance.partialTick(), instance.isPaused() ? 0.0F : instance.rate());
    }
}
