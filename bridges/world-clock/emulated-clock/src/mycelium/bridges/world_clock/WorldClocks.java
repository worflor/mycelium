package mycelium.bridges.world_clock;

import mycelium.world_clock.v1.model.Applied;
import mycelium.world_clock.v1.model.ClientClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.ReferenceClockModel;
import mycelium.world_clock.v1.model.StandardTimelines;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Mycelium's {@code world-clock} bridge, variant {@code emulated-clock} (Minecraft before 26.1): {@link ClockPort} on a game
 * that has one clock. The overworld clock <em>is</em> vanilla's {@code dayTime} (rate one; paused is the daylight rule off;
 * a set or an add is {@code setDayTime}); every other clock a mod declares lives in one saved data on the overworld, advances
 * on the server tick this variant is called from, and syncs to clients with a small payload on join and on change. Timelines,
 * markers and periods are the pure model's ({@link ClockModel}), the same arithmetic 26.x's game does.
 *
 * <p>What the mod supplies, once, and why (no mixin is involved):
 * <ul>
 *   <li>call {@link #onServerTick()} every server tick and {@link #onPlayerJoin} when a player joins: the game before 26.1
 *       has no clock manager to do it, and the loaders name those events differently;</li>
 *   <li>a {@link Transport}, only if it reads a clock of its own on a client: how to send the payload and, on the client,
 *       to hand it to {@link Client#receive}.</li>
 * </ul>
 *
 * <h2>Honest limits</h2>
 * A mod's own clocks are fully real to every mod that reads them. Vanilla's sky, moon, mobs and beds follow {@code dayTime},
 * which is the overworld clock here: the overworld's rate is one (a {@link ClockCommand.SetRate} to anything else is refused,
 * not pretended), sleeping skips to the next multiple of 24000 whatever wake marker a timeline declares, and no other
 * clock moves vanilla. A mod that wants vanilla bent takes {@code emulated-clock-takeover}, which is offered and never default.
 *
 * <p>This class is open on purpose (decision 0016: offer, never police). Extend it and override the seams, or write your own
 * {@link ClockPort} and name it with {@code bridges { override("world-clock", "...") }}.
 */
public class WorldClocks implements ClockPort {
    private static final ClockId OVERWORLD = StandardTimelines.OVERWORLD;
    private static final String WAKE = StandardTimelines.WAKE_UP_FROM_SLEEP;
    /** Where this variant keeps a mod's clocks in the world's saved data. */
    public static final String STORAGE_NAME = "mycelium_world_clocks";

    /** Where the mod's clocks are kept between sessions. The game's saved data is the one that matters; the rest is for tests. */
    public interface Storage {
        /** What was saved, or an empty string. */
        String load();

        /** The clocks are now {@code live}'s, and the game should ask it for their text whenever it saves. */
        void attach(Supplier<String> live);

        /** The clocks changed: the next save must write them. */
        void changed();

        /** The same storage after a save and a load, by the path the game itself uses for them. */
        Storage reloaded();

        /** Storage in memory: nothing outlives the process. For tests, and for a port not attached to a world. */
        static Storage memory(String initial) {
            return new Storage() {
                private Supplier<String> live;
                private String text = initial;

                @Override
                public String load() {
                    return text;
                }

                @Override
                public void attach(Supplier<String> source) {
                    this.live = source;
                }

                @Override
                public void changed() {
                    if (live != null) {
                        text = live.get();
                    }
                }

                @Override
                public Storage reloaded() {
                    return memory(live != null ? live.get() : text);
                }
            };
        }
    }

    protected final MinecraftServer server;
    private final ClockWorld world;
    private final ClockModel model;
    private final Transport transport;
    private final Storage storage;
    /** The declared clocks other than the overworld's, which is vanilla's. */
    private final Map<ClockId, ClockState> held = new LinkedHashMap<>();
    /** Clocks the save has that this declaration does not: kept, so a declaration left out for a session loses nothing. */
    private final Map<ClockId, ClockState> unclaimed = new LinkedHashMap<>();
    private boolean lastAdvance;
    private boolean ticked;

    protected WorldClocks(MinecraftServer server, ClockWorld world, ClockModel model, Transport transport, Storage storage) {
        this.server = java.util.Objects.requireNonNull(server, "server");
        this.world = java.util.Objects.requireNonNull(world, "world");
        this.model = java.util.Objects.requireNonNull(model, "model");
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.storage = java.util.Objects.requireNonNull(storage, "storage");
        Map<ClockId, ClockState> saved = ClockText.read(storage.load());
        for (ClockId clock : world.clocks()) {
            if (!clock.equals(OVERWORLD)) {
                ClockState s = saved.remove(clock);
                held.put(clock, s != null ? s : ClockState.start());
            }
        }
        unclaimed.putAll(saved);
        storage.attach(this::text);
    }

    /** The port over {@code server}, for the clocks the mod declares; their state is kept in the overworld's saved data. */
    public static WorldClocks of(MinecraftServer server, ClockWorld world, Transport transport) {
        return attached(server, world, transport, STORAGE_NAME);
    }

    /** As {@link #of(MinecraftServer, ClockWorld, Transport)}, with the clocks kept under another name in the overworld's saved data (for tests that must not touch the mod's). */
    public static WorldClocks attached(MinecraftServer server, ClockWorld world, Transport transport, String storageName) {
        return new WorldClocks(server, world, new ReferenceClockModel(), transport, ClockStore.of(server, storageName));
    }

    /** As above, with no transport: no clock of the mod's is read on a client. */
    public static WorldClocks of(MinecraftServer server, ClockWorld world) {
        return of(server, world, Transport.NONE);
    }

    /** A port that keeps its clocks in {@code storage} (for tests: {@link Storage#memory}). */
    public static WorldClocks over(MinecraftServer server, ClockWorld world, Transport transport, Storage storage) {
        return new WorldClocks(server, world, new ReferenceClockModel(), transport, storage);
    }

    /** The same clocks as they are after a save and a load: what a restart would give a port over this one's storage. */
    public WorldClocks reloaded() {
        return new WorldClocks(server, world, model, transport, storage.reloaded());
    }

    /** The storage as a restart would find it: what this port saved, by the game's own save and load. */
    public Storage reloadedStorage() {
        return storage.reloaded();
    }

    private String text() {
        Map<ClockId, ClockState> all = new LinkedHashMap<>(held);
        all.putAll(unclaimed);
        return ClockText.write(all);
    }

    // ---------------------------------------------------------------------------------------------------- the port

    @Override
    public ClockWorld world() {
        return world;
    }

    @Override
    public long gameTime() {
        return Game.gameTime(server);
    }

    @Override
    public ClockState state(ClockId clock) {
        if (clock.equals(OVERWORLD) && world.clocks().contains(OVERWORLD)) {
            // the game before 26.1 lets /time add take day time below zero; the model's clocks do not go there
            return new ClockState(Math.max(0L, Game.dayTime(server)), 0.0F, 1.0F, !Game.advanceTime(server));
        }
        ClockState s = held.get(clock);
        if (s == null) {
            throw new IllegalArgumentException("no clock " + clock);
        }
        return s;
    }

    @Override
    public Applied apply(ClockId clock, ClockCommand command) {
        ClockState before = state(clock);
        if (clock.equals(OVERWORLD) && command instanceof ClockCommand.SetRate r && r.rate() != 1.0F) {
            throw new UnsupportedOperationException("the overworld here is vanilla's day time, which runs at rate one; to give it"
                    + " another rate, take emulated-clock-takeover (or move the mod's own clock, which has any rate)");
        }
        Applied applied = model.apply(before, command, id -> world.marker(clock, id));
        write(clock, before, applied.state());
        return new Applied(state(clock), applied.move());
    }

    /** Puts a clock's new state where it lives: the game's day time and rule for the overworld, the saved data for the rest. */
    private void write(ClockId clock, ClockState before, ClockState after) {
        if (clock.equals(OVERWORLD)) {
            if (after.totalTicks() != before.totalTicks()) {
                Game.setDayTime(server, after.totalTicks());
            }
            if (after.paused() != before.paused()) {
                Game.setAdvanceTime(server, !after.paused());
            }
            return;
        }
        if (!after.equals(before)) {
            held.put(clock, after);
            storage.changed();
            transport.toAll(server, ClockWire.encode(wire(Game.advanceTime(server), clock)));
        }
    }

    /**
     * One server tick of the mod's clocks: they advance by the model, unless {@code advanceTime} is off. The overworld is
     * vanilla's and has moved on its own. The mod's tick hook is {@link #onServerTick()}, which calls this.
     */
    @Override
    public void tick(boolean advanceTime) {
        boolean changed = false;
        for (Map.Entry<ClockId, ClockState> e : held.entrySet()) {
            ClockState next = model.tick(e.getValue(), advanceTime);
            if (!next.equals(e.getValue())) {
                e.setValue(next);
                changed = true;
            }
        }
        if (changed) {
            storage.changed();
        }
    }

    @Override
    public Optional<Applied> sleep(String dimension, boolean advanceTime) {
        Optional<ClockId> found = world.defaultClock(dimension);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ClockId clock = found.get();
        ClockState before = state(clock);
        Move move = model.sleep(before, world.marker(clock, WAKE), advanceTime);
        write(clock, before, move.state());
        return Optional.of(new Applied(state(clock), Optional.of(move.result())));
    }

    @Override
    public ClockSync sync(boolean advanceTime) {
        return sync(advanceTime, world.clocks().toArray(new ClockId[0]));
    }

    @Override
    public ClockSync sync(ClockId clock, boolean advanceTime) {
        return sync(advanceTime, clock);
    }

    private ClockSync sync(boolean advanceTime, ClockId... clocks) {
        Map<ClockId, NetworkState> out = new LinkedHashMap<>();
        for (ClockId clock : clocks) {
            out.put(clock, model.network(state(clock), advanceTime));
        }
        return new ClockSync(gameTime(), out);
    }

    /** What goes to a client: the mod's own clocks, never the overworld (the game syncs its day time itself). */
    private ClockSync wire(boolean advanceTime, ClockId... clocks) {
        List<ClockId> own = new ArrayList<>();
        for (ClockId clock : clocks.length == 0 ? world.clocks().toArray(new ClockId[0]) : clocks) {
            if (!clock.equals(OVERWORLD)) {
                own.add(clock);
            }
        }
        return sync(advanceTime, own.toArray(new ClockId[0]));
    }

    // ---------------------------------------------------------------------------------------------------- hooks

    /**
     * The mod calls this every server tick. The mod's clocks advance unless the daylight rule is off or the server is
     * frozen or stepping (the tick rate manager, where the game has one), as 26.x's clocks do; and when the rule changes,
     * clients are told, since a clock the rule stops is sent with rate zero.
     */
    public void onServerTick() {
        boolean advance = Game.advanceTime(server);
        if (Game.runsNormally(server)) {
            tick(advance);
        }
        if (ticked && advance != lastAdvance && hasOwnClocks()) {
            transport.toAll(server, ClockWire.encode(wire(advance)));
        }
        lastAdvance = advance;
        ticked = true;
    }

    /** The mod calls this when a player joins: they are sent every clock of the mod's. */
    public void onPlayerJoin(ServerPlayer player) {
        if (hasOwnClocks()) {
            transport.toPlayer(player, ClockWire.encode(wire(Game.advanceTime(server))));
        }
    }

    private boolean hasOwnClocks() {
        return !held.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- the declaration against the game

    /** Where the declaration asks for what this variant cannot give. Empty when it asks for nothing more than the game before 26.1 has. */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        world.defaultClock("minecraft:overworld").ifPresent(c -> {
            if (!c.equals(OVERWORLD)) {
                out.add("the overworld's default clock is declared " + c + ", but vanilla sleeps by its day time, which is "
                        + OVERWORLD + " here; take emulated-clock-takeover to bend vanilla to another clock");
            }
        });
        world.marker(OVERWORLD, WAKE).ifPresent(m -> {
            if (m.ticks() != 0 || m.periodTicks().orElse(0) != 24000) {
                out.add("the overworld's wake marker is declared at " + m.ticks() + " of " + m.periodTicks().orElse(0)
                        + ", but vanilla skips to the next multiple of 24000; take emulated-clock-takeover to honour another");
            }
        });
        return out;
    }

    // ---------------------------------------------------------------------------------------------------- clients

    /**
     * A client's side. The overworld is the client's own day time (the game syncs it); the mod's clocks are what
     * {@link #receive} was last handed, extrapolated by rate as 26.x's client does. Call {@link #receive} on the client
     * thread, and {@link #reset} when leaving a world.
     */
    public static final class Client {
        private static ClientClocks clocks = new ClientClocks(new ReferenceClockModel());
        private static long lastRead;

        private Client() {
        }

        /** A payload from the server's {@link Transport}. */
        public static void receive(byte[] payload) {
            clocks.handle(ClockWire.decode(payload));
        }

        /** Forgets what the server sent, as leaving a world does. */
        public static void reset() {
            clocks = new ClientClocks(new ReferenceClockModel());
            lastRead = 0L;
        }

        /** What a client knows of a clock at its current game time: ticks, fraction and the rate it runs at (zero when paused). */
        public static NetworkState read(Level level, ClockId clock) {
            if (clock.equals(OVERWORLD)) {
                return new NetworkState(Math.max(0L, level.getDayTime()), 0.0F, 1.0F);
            }
            long now = level.getGameTime();
            if (now < lastRead) {
                reset();
            }
            lastRead = now;
            clocks.tick(now);
            return clocks.read(clock);
        }

        /** The overworld clock, which most mods read. */
        public static NetworkState overworld(Level level) {
            return read(level, OVERWORLD);
        }
    }
}
