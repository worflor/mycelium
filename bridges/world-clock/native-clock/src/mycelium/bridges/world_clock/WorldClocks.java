package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.Applied;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.StandardTimelines;
import mycelium.world_clock.v1.model.Timeline;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.clock.ClockTimeMarker;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mycelium's {@code world-clock} bridge, variant {@code native-clock} (Minecraft 26.1 on): {@link ClockPort} over the game's
 * own clocks. A state is {@link ServerClockManager}'s, a command is its method, a marker is a {@code ClockTimeMarker} the
 * game registers from the timelines in its data, and what clients are told is the game's own time packet. Nothing here is
 * arithmetic: the game is the model, and the conformance cases hold the pure model to it.
 *
 * <p>The game advances its clocks itself, once per server tick, while the {@code advance_time} rule holds, and syncs them to
 * clients on change and on join. So on this variant {@link #onServerTick()} and {@link #onPlayerJoin} do nothing, and
 * {@link #tick} (the port's one-server-tick step) exists for the conformance cases, which run it in a test world.
 *
 * <p>A mod's own clocks and timelines are datapack JSON ({@code data/<ns>/world_clock/<id>.json}, {@code
 * data/<ns>/timeline/<id>.json}); {@link ClockDatapack} writes them from the declaration, and {@link #problems()} reports
 * where the game's data and the declaration disagree. The game loads clocks when the world loads: a {@code /reload} does not
 * add one.
 *
 * <p>This class is open on purpose (decision 0016: offer, never police). Extend it and override the seams, or write your own
 * {@link ClockPort} and name it with {@code bridges { override("world-clock", "...") }}.
 */
public class WorldClocks implements ClockPort {
    /** The marker sleeping moves a dimension's default clock to. */
    private static final ResourceKey<ClockTimeMarker> WAKE_UP_FROM_SLEEP = ClockTimeMarkers.WAKE_UP_FROM_SLEEP;

    protected final MinecraftServer server;
    private final ClockWorld world;
    private final Map<ClockId, Holder<WorldClock>> holders = new HashMap<>();

    protected WorldClocks(MinecraftServer server, ClockWorld world) {
        this.server = java.util.Objects.requireNonNull(server, "server");
        this.world = java.util.Objects.requireNonNull(world, "world");
    }

    /** The port over {@code server}'s clocks, for the clocks and timelines the mod declares. */
    public static WorldClocks of(MinecraftServer server, ClockWorld world) {
        return new WorldClocks(server, world);
    }

    /** As {@link #of(MinecraftServer, ClockWorld)}; the transport is not used here (the game syncs its clocks) and is accepted so a mod's code is the same on every version. */
    public static WorldClocks of(MinecraftServer server, ClockWorld world, Transport transport) {
        return new WorldClocks(server, world);
    }

    // ---------------------------------------------------------------------------------------------------- the seams

    /** The game's clock manager. */
    protected ServerClockManager manager() {
        return server.clockManager();
    }

    /** A clock's registry holder; an undeclared or unregistered clock is an {@link IllegalArgumentException}. */
    protected Holder<WorldClock> holder(ClockId clock) {
        Holder<WorldClock> cached = holders.get(clock);
        if (cached != null) {
            return cached;
        }
        ResourceKey<WorldClock> key = ResourceKey.create(Registries.WORLD_CLOCK, Identifier.parse(clock.id()));
        Optional<? extends Holder<WorldClock>> found = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(key);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no clock " + clock + " is registered: add data/<namespace>/world_clock/<id>.json"
                    + " (ClockDatapack writes it from the declaration)");
        }
        holders.put(clock, found.get());
        return found.get();
    }

    /** Whether the game advances its clocks this tick: the {@code advance_time} rule. */
    protected boolean advanceTimeRule() {
        return Game.advanceTime(server);
    }

    private static ClockId idOf(Holder<WorldClock> holder) {
        return new ClockId(holder.unwrapKey().orElseThrow().identifier().toString());
    }

    private static ResourceKey<ClockTimeMarker> markerKey(String id) {
        return ResourceKey.create(ClockTimeMarkers.ROOT_ID, Identifier.parse(id));
    }

    // ---------------------------------------------------------------------------------------------------- the port

    @Override
    public ClockWorld world() {
        return world;
    }

    @Override
    public long gameTime() {
        return server.overworld().getGameTime();
    }

    @Override
    public ClockState state(ClockId clock) {
        return Game.state(server, holder(clock));
    }

    @Override
    public Applied apply(ClockId clock, ClockCommand command) {
        Holder<WorldClock> holder = holder(clock);
        ServerClockManager clocks = manager();
        if (command instanceof ClockCommand.SetTicks c) {
            clocks.setTotalTicks(holder, c.totalTicks());
        } else if (command instanceof ClockCommand.AddTicks c) {
            clocks.addTicks(holder, c.ticks());
        } else if (command instanceof ClockCommand.Pause) {
            clocks.setPaused(holder, true);
        } else if (command instanceof ClockCommand.Resume) {
            clocks.setPaused(holder, false);
        } else if (command instanceof ClockCommand.SetRate c) {
            clocks.setRate(holder, c.rate());
        } else {
            ClockCommand.MoveToMarker c = (ClockCommand.MoveToMarker) command;
            MoveResult result = Game.move(server, holder, markerKey(c.markerId()));
            return new Applied(state(clock), Optional.of(result));
        }
        return new Applied(state(clock), Optional.empty());
    }

    /**
     * One server tick of the game's own clock step ({@code ServerClockManager.tick}), which the server runs itself every tick:
     * for the conformance cases, which call it in a test world. The game reads the {@code advance_time} rule itself, so
     * {@code advanceTime} must be what the rule is; a port that ticked as if it were otherwise would not be the game's.
     */
    @Override
    public void tick(boolean advanceTime) {
        requireRule(advanceTime);
        manager().tick();
    }

    @Override
    public Optional<Applied> sleep(String dimension, boolean advanceTime) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
        if (level == null) {
            throw new IllegalArgumentException("no dimension " + dimension);
        }
        Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
        if (clock.isEmpty()) {
            return Optional.empty();
        }
        ClockId id = idOf(clock.get());
        if (!advanceTime) {
            return Optional.of(new Applied(state(id), Optional.of(MoveResult.NOT_MOVED)));
        }
        MoveResult result = Game.move(server, clock.get(), WAKE_UP_FROM_SLEEP);
        return Optional.of(new Applied(state(id), Optional.of(result)));
    }

    @Override
    public ClockSync sync(boolean advanceTime) {
        requireRule(advanceTime);
        Map<ClockId, NetworkState> all = new LinkedHashMap<>();
        for (ClockId clock : world.clocks()) {
            all.put(clock, network(clock));
        }
        return new ClockSync(gameTime(), all);
    }

    @Override
    public ClockSync sync(ClockId clock, boolean advanceTime) {
        requireRule(advanceTime);
        Map<ClockId, NetworkState> one = new LinkedHashMap<>();
        one.put(clock, network(clock));
        return new ClockSync(gameTime(), one);
    }

    private NetworkState network(ClockId clock) {
        return Game.network(server, holder(clock));
    }

    private void requireRule(boolean advanceTime) {
        if (advanceTime != advanceTimeRule()) {
            throw new IllegalStateException("the game reads the advance_time rule itself (it is " + advanceTimeRule()
                    + "); a port asked for " + advanceTime + " would not be the game's");
        }
    }

    // ---------------------------------------------------------------------------------------------------- hooks

    /** Nothing to do: the game advances and syncs its clocks. Present so a mod calls the same hook on every version. */
    public void onServerTick() {
    }

    /** Nothing to do: the game sends a joining player its clocks. */
    public void onPlayerJoin(ServerPlayer player) {
    }

    // ---------------------------------------------------------------------------------------------------- the declaration against the game

    /**
     * Where the game's data and the declaration disagree: a declared clock the game does not have, a declared timeline or
     * marker that is missing or differs (period, tick, whether commands list it). Empty when they agree. A timeline the
     * game has and the declaration lacks is not a problem (the game's own {@code minecraft:day} carries more than the model
     * does); one the declaration names is held to what it says.
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        for (ClockId clock : world.clocks()) {
            try {
                holder(clock);
            } catch (IllegalArgumentException missing) {
                out.add(missing.getMessage());
            }
        }
        var timelines = server.registryAccess().lookupOrThrow(Registries.TIMELINE);
        for (Timeline declared : world.timelines()) {
            Identifier id = Identifier.parse(declared.id());
            Optional<? extends Holder<net.minecraft.world.timeline.Timeline>> found = timelines.get(ResourceKey.create(Registries.TIMELINE, id));
            if (found.isEmpty()) {
                out.add("no timeline " + declared.id() + " is registered: add data/" + id.getNamespace() + "/timeline/" + id.getPath() + ".json");
                continue;
            }
            net.minecraft.world.timeline.Timeline actual = found.get().value();
            String actualClock = actual.clock().unwrapKey().orElseThrow().identifier().toString();
            if (!actualClock.equals(declared.clock().id())) {
                out.add("timeline " + declared.id() + " is on clock " + actualClock + ", declared on " + declared.clock());
            }
            Optional<Integer> period = actual.periodTicks();
            if (!period.map(p -> declared.periodTicks().isPresent() && declared.periodTicks().getAsInt() == p)
                    .orElse(declared.periodTicks().isEmpty())) {
                out.add("timeline " + declared.id() + " has period " + period.map(String::valueOf).orElse("none") + ", declared "
                        + (declared.periodTicks().isPresent() ? String.valueOf(declared.periodTicks().getAsInt()) : "none"));
            }
            Map<String, ClockTimeMarker> markers = new LinkedHashMap<>();
            actual.registerTimeMarkers((key, marker) -> markers.put(key.identifier().toString(), marker));
            for (Marker want : declared.markers()) {
                ClockTimeMarker got = markers.get(want.id());
                if (got == null) {
                    out.add("timeline " + declared.id() + " has no marker " + want.id());
                } else if (got.ticks() != want.ticks() || got.showInCommands() != want.showInCommands()) {
                    out.add("marker " + want.id() + " of " + declared.id() + " is at " + got.ticks() + " (commands " + got.showInCommands()
                            + "), declared at " + want.ticks() + " (commands " + want.showInCommands() + ")");
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- clients

    /**
     * A client's side. On 26.1 and later the game keeps the client's clocks itself; this reads them. {@link #receive} and
     * {@link #reset} exist so a mod's client code is the same on every version.
     */
    public static final class Client {
        private Client() {
        }

        /** What a client knows of a clock at its current game time: ticks, fraction and the rate it runs at (zero when paused). */
        public static NetworkState read(Level level, ClockId clock) {
            var key = ResourceKey.create(Registries.WORLD_CLOCK, Identifier.parse(clock.id()));
            return Game.clientRead(level, level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(key));
        }

        /** The overworld clock, which most mods read. */
        public static NetworkState overworld(Level level) {
            return read(level, StandardTimelines.OVERWORLD);
        }

        /** Nothing to do: the game's own packet carries every clock. */
        public static void receive(byte[] payload) {
        }

        /** Nothing to do: the game resets its client's clocks when it leaves a world. */
        public static void reset() {
        }
    }
}
