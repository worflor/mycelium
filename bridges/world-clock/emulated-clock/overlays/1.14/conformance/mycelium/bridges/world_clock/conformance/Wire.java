package mycelium.bridges.world_clock.conformance;

import mycelium.bridges.world_clock.ClockWire;
import mycelium.bridges.world_clock.Transport;
import mycelium.bridges.world_clock.WorldClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.ReferenceClockModel;
import mycelium.world_clock.v1.model.StandardTimelines;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the conformance cases need of the game that differs by variant (decision 0016): the bridge's own port, the game's
 * rule and its own reading of the overworld's day time, and what a client is handed. This is the {@code emulated-clock}
 * variant's (Minecraft before 26.1): the game has one {@code dayTime}, the cases' port keeps the mod's clocks in memory (so a
 * case never touches the mod's own saved clocks), and a client is handed the bytes of {@link ClockWire}. The daylight rule is
 * read the way Minecraft 1.14 to 1.21.10 name it, and the overworld is found the way 1.14 and 1.15 do.
 */
final class Wire {
    private static final ClockModel MODEL = new ReferenceClockModel();
    private static final ClockConformance.Ports PORTS = (level, world) ->
            WorldClocks.over(level.getServer(), world, Transport.NONE, WorldClocks.Storage.memory(""));

    private Wire() {
    }

    /** 1.14 and 1.15 find the overworld through the dimension type. */
    private static ServerLevel overworld(MinecraftServer server) {
        return server.getLevel(DimensionType.OVERWORLD);
    }

    static ClockConformance.Ports ports() {
        return PORTS;
    }

    // ---------------------------------------------------------------------------------------------------- the game's own

    static boolean ruleOn(ServerLevel level) {
        return level.getServer().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
    }

    static void setRule(ServerLevel level, boolean on) {
        level.getServer().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(on, level.getServer());
    }

    /** The overworld's day time as the game itself reads it (the level's, not the bridge's), never below zero. */
    static long gameOverworld(ServerLevel level) {
        return Math.max(0L, overworld(level.getServer()).getDayTime());
    }

    static void gameSetOverworld(ServerLevel level, long ticks) {
        overworld(level.getServer()).setDayTime(ticks);
    }

    /** The overworld is vanilla's day time: rate one, not for a mod to change. */
    static boolean overworldRateFixed() {
        return true;
    }

    /** The port holds its own clocks and the game moves the overworld itself, so stepping the port moves nothing else. */
    static void isolate(ClockPort port, ClockId keep, Runnable body) {
        body.run();
    }

    /** The game before 26.1 has no marker code to ask. */
    static Boolean gameAtMarker(ServerLevel level, ClockId clock, String markerId) {
        return null;
    }

    /** The game before 26.1 has no timelines to ask. */
    static long[] gamePeriods(ServerLevel level, String timelineId) {
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- clients

    /** What a client is handed now: the port's sync, written as the bridge sends it and read back. */
    static ClockSync delivered(ServerLevel level, ClockPort port, boolean rule) {
        return ClockWire.decode(ClockWire.encode(port.sync(rule)));
    }

    /** The game's own client formula over a delivered sync: there is none before 26.1, the bridge's client is the model's. */
    interface RealClient {
        NetworkState read(ClockId clock, long gameTime);
    }

    static RealClient realClient(ServerLevel level, ClockSync delivered) {
        return null;
    }

    // ---------------------------------------------------------------------------------------------------- this variant's own cases

    static List<ClockConformance.Case> extraCases() {
        List<ClockConformance.Case> cases = new ArrayList<>();
        cases.add(new ClockConformance.Case("save_and_reload", level -> saveAndReload((ServerLevel) level)));
        cases.add(new ClockConformance.Case("hooks_tell_clients", level -> hooksTellClients((ServerLevel) level)));
        cases.add(new ClockConformance.Case("wire_round_trip", level -> wireRoundTrip()));
        cases.add(new ClockConformance.Case("client_follows", level -> clientFollows((ServerLevel) level)));
        cases.add(new ClockConformance.Case("limits_are_named", level -> limitsAreNamed((ServerLevel) level)));
        return cases;
    }

    /** What a mod's clocks keep across a save and a load, bit for bit, by the path the game itself saves and loads saved data. */
    private static void saveAndReload(ServerLevel level) {
        MinecraftServer server = level.getServer();
        WorldClocks port = WorldClocks.attached(server, ClockConformance.WORLD, Transport.NONE, "mycelium_conformance_clocks");
        ClockId calendar = ClockConformance.CALENDAR;
        port.apply(calendar, new ClockCommand.SetRate(0.3F));
        port.apply(calendar, new ClockCommand.SetTicks(4321L));
        for (int i = 0; i < 7; i++) {
            port.tick(true);
        }
        port.apply(StandardTimelines.THE_END, new ClockCommand.SetRate(2.5F));
        port.apply(StandardTimelines.THE_END, new ClockCommand.Pause());
        ClockState calendarNow = port.state(calendar);
        ClockState endNow = port.state(StandardTimelines.THE_END);
        ClockConformance.expect("a clock with a fraction, a rate and a pause to save: " + calendarNow, calendarNow.partialTick() != 0.0F && calendarNow.rate() == 0.3F);

        WorldClocks back = port.reloaded();
        ClockConformance.expect("a mod's clock reloads exactly: " + back.state(calendar) + " and " + calendarNow, back.state(calendar).equals(calendarNow));
        ClockConformance.expect("and another: " + back.state(StandardTimelines.THE_END) + " and " + endNow, back.state(StandardTimelines.THE_END).equals(endNow));

        // a clock the save has and the declaration lacks is kept, so leaving a declaration out for a session loses nothing
        WorldClocks vanillaOnly = WorldClocks.over(server, ClockWorld.vanilla(), Transport.NONE, WorldClocks.Storage.memory(""));
        ClockConformance.expect("vanilla alone declares no calendar", vanillaOnly.world().clocks().size() == 2);
        WorldClocks without = WorldClocks.over(server, ClockWorld.vanilla(), Transport.NONE, port.reloadedStorage());
        without.tick(true);
        WorldClocks again = WorldClocks.over(server, ClockConformance.WORLD, Transport.NONE, without.reloadedStorage());
        ClockConformance.expect("a clock the declaration left out for a session is still in the save: " + again.state(calendar) + " and " + calendarNow,
                again.state(calendar).equals(calendarNow));

        // an empty or damaged save starts clocks again from tick zero
        WorldClocks damaged = WorldClocks.over(server, ClockConformance.WORLD, Transport.NONE, WorldClocks.Storage.memory("garbage\nmycelium_conformance:calendar 12 zz zz 1\n"));
        ClockConformance.expect("an unreadable save gives a new clock: " + damaged.state(calendar), damaged.state(calendar).equals(ClockState.start()));
    }

    /** A recording transport sees what the hooks say: changes go to everyone, joiners get every clock, the rule's change is announced. */
    private static void hooksTellClients(ServerLevel level) {
        MinecraftServer server = level.getServer();
        List<byte[]> everyone = new ArrayList<>();
        List<byte[]> joiners = new ArrayList<>();
        Transport recorder = new Transport() {
            @Override
            public void toAll(MinecraftServer s, byte[] payload) {
                everyone.add(payload);
            }

            @Override
            public void toPlayer(ServerPlayer player, byte[] payload) {
                joiners.add(payload);
            }
        };
        WorldClocks port = WorldClocks.over(server, ClockConformance.WORLD, recorder, WorldClocks.Storage.memory(""));
        ClockId calendar = ClockConformance.CALENDAR;
        boolean rule = ruleOn(level);
        setRule(level, true);
        try {
            port.apply(calendar, new ClockCommand.SetRate(2.0F));
            ClockConformance.expect("a change to a mod's clock is told to everyone, once", everyone.size() == 1);
            ClockSync told = ClockWire.decode(everyone.get(0));
            ClockConformance.expect("and says that clock alone, as the model sends it: " + told.clocks(),
                    told.clocks().equals(Map.of(calendar, MODEL.network(port.state(calendar), true))) && told.gameTime() == overworld(server).getGameTime());
            port.apply(StandardTimelines.OVERWORLD, new ClockCommand.AddTicks(0));
            ClockConformance.expect("a change to the overworld is not told: the game syncs its day time", everyone.size() == 1);

            port.onPlayerJoin(null);
            ClockConformance.expect("a joiner is told once", joiners.size() == 1);
            ClockSync all = ClockWire.decode(joiners.get(0));
            Map<ClockId, NetworkState> want = new LinkedHashMap<>();
            want.put(StandardTimelines.THE_END, MODEL.network(port.state(StandardTimelines.THE_END), true));
            want.put(calendar, MODEL.network(port.state(calendar), true));
            ClockConformance.expect("with every clock of the mod's and not the overworld: " + all.clocks() + " and " + want, all.clocks().equals(want));

            // the hook advances the clocks by the model, and tells clients when the rule changes what they extrapolate
            ClockState before = port.state(calendar);
            int sent = everyone.size();
            port.onServerTick();
            ClockConformance.expect("the tick hook advances a mod's clock as the model does", port.state(calendar).equals(MODEL.tick(before, true)));
            ClockConformance.expect("and says nothing while nothing changed", everyone.size() == sent);
            setRule(level, false);
            ClockState frozen = port.state(calendar);
            port.onServerTick();
            ClockConformance.expect("with the rule off the hook does not move the clock", port.state(calendar).equals(frozen));
            ClockConformance.expect("and tells clients, who must stop extrapolating", everyone.size() == sent + 1
                    && ClockWire.decode(everyone.get(sent)).clocks().get(calendar).rate() == 0.0F);
        } finally {
            setRule(level, rule);
        }
    }

    /** The payload round trips every field of every clock, floats exactly, and refuses what is not a payload. */
    private static void wireRoundTrip() {
        Map<ClockId, NetworkState> clocks = new LinkedHashMap<>();
        clocks.put(new ClockId("a:one"), new NetworkState(0L, 0.0F, 1.0F));
        clocks.put(new ClockId("b:two"), new NetworkState(Long.MAX_VALUE / 3, 0.99999994F, 0.1F));
        clocks.put(new ClockId("c:three"), new NetworkState(123456789012L, 0.25F, 1.0E9F));
        ClockSync sync = new ClockSync(987654321L, clocks);
        ClockConformance.expect("a payload round trips", ClockWire.decode(ClockWire.encode(sync)).equals(sync));
        byte[] bytes = ClockWire.encode(sync);
        for (byte[] bad : new byte[][] {new byte[0], new byte[] {2, 0, 0}, java.util.Arrays.copyOf(bytes, bytes.length - 3)}) {
            boolean refused = false;
            try {
                ClockWire.decode(bad);
            } catch (IllegalArgumentException expected) {
                refused = true;
            }
            ClockConformance.expect("a payload that is not one is refused, not guessed at", refused);
        }
    }

    /** A client given a payload reads the clock at the game time it was sent, and the overworld is the client's own day time. */
    private static void clientFollows(ServerLevel level) {
        WorldClocks.Client.reset();
        try {
            ClockId calendar = ClockConformance.CALENDAR;
            Map<ClockId, NetworkState> clocks = new LinkedHashMap<>();
            clocks.put(calendar, new NetworkState(4321L, 0.5F, 1.0F));
            WorldClocks.Client.receive(ClockWire.encode(new ClockSync(level.getGameTime(), clocks)));
            NetworkState read = WorldClocks.Client.read(level, calendar);
            ClockConformance.expect("a client reads a clock as it was sent: " + read, read.equals(new NetworkState(4321L, 0.5F, 1.0F)));
            ClockConformance.expect("a clock never heard of reads tick zero at rate one", WorldClocks.Client.read(level, new ClockId("x:never")).equals(new NetworkState(0L, 0.0F, 1.0F)));
            ClockConformance.expect("the overworld is the client's own day time: " + WorldClocks.Client.overworld(level),
                    WorldClocks.Client.overworld(level).totalTicks() == Math.max(0L, level.getDayTime()));
        } finally {
            WorldClocks.Client.reset();
        }
    }

    /** What the variant cannot give is named, not hidden: a declaration that asks vanilla to follow another clock gets a report. */
    private static void limitsAreNamed(ServerLevel level) {
        WorldClocks vanilla = WorldClocks.over(level.getServer(), ClockWorld.vanilla(), Transport.NONE, WorldClocks.Storage.memory(""));
        ClockConformance.expect("vanilla asks for nothing this variant lacks: " + vanilla.problems(), vanilla.problems().isEmpty());
        WorldClocks bent = WorldClocks.over(level.getServer(), ClockConformance.WORLD.withDefault("minecraft:overworld", ClockConformance.CALENDAR),
                Transport.NONE, WorldClocks.Storage.memory(""));
        ClockConformance.expect("asking vanilla to sleep by another clock is reported: " + bent.problems(), bent.problems().size() == 1);
    }
}
