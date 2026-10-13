package mycelium.bridges.world_clock.conformance;

import com.mojang.serialization.DataResult;
import io.netty.buffer.Unpooled;
import mycelium.bridges.world_clock.ClockDatapack;
import mycelium.bridges.world_clock.WorldClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.NetworkState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.timeline.Timeline;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the conformance cases need of the game that differs by variant (decision 0016): the bridge's own port, the game's
 * rule and its own reading of the overworld clock, the time packet as the game sends it, the game's period and marker code,
 * and the real client's formula where the client classes are present. This is the {@code native-clock} variant's
 * (Minecraft 26.1 on): the game keeps its own clocks, so a case that steps one steps the game's manager, and the other clocks
 * are paused around it so the world's day does not move.
 */
final class Wire {
    private static final ClockConformance.Ports PORTS = (level, world) -> WorldClocks.of(level.getServer(), world);

    private Wire() {
    }

    static ClockConformance.Ports ports() {
        return PORTS;
    }

    // ---------------------------------------------------------------------------------------------------- the game's own

    static boolean ruleOn(ServerLevel level) {
        return level.getServer().getGlobalGameRules().get(GameRules.ADVANCE_TIME);
    }

    static void setRule(ServerLevel level, boolean on) {
        level.getServer().getGlobalGameRules().set(GameRules.ADVANCE_TIME, on, level.getServer());
    }

    /** The overworld clock as the game itself reads it (the level's, not the bridge's). */
    static long gameOverworld(ServerLevel level) {
        return level.getOverworldClockTime();
    }

    static void gameSetOverworld(ServerLevel level, long ticks) {
        MinecraftServer server = level.getServer();
        server.clockManager().setTotalTicks(holder(server, new ClockId("minecraft:overworld")), ticks);
    }

    /** Whether the overworld clock's rate cannot be changed (the game before 26.1 has one clock at rate one). */
    static boolean overworldRateFixed() {
        return false;
    }

    /** Runs {@code body} with every clock but {@code keep} paused, so stepping the game's manager moves only that one. */
    static void isolate(ClockPort port, ClockId keep, Runnable body) {
        List<ClockId> paused = new ArrayList<>();
        for (ClockId other : port.world().clocks()) {
            if (!other.equals(keep) && !port.state(other).paused()) {
                port.apply(other, new ClockCommand.Pause());
                paused.add(other);
            }
        }
        try {
            body.run();
        } finally {
            for (ClockId other : paused) {
                port.apply(other, new ClockCommand.Resume());
            }
        }
    }

    /** The game's own answer to "is this clock at this marker" ({@code ServerClockManager.isAtTimeMarker}). */
    static Boolean gameAtMarker(ServerLevel level, ClockId clock, String markerId) {
        MinecraftServer server = level.getServer();
        return server.clockManager().isAtTimeMarker(holder(server, clock), ResourceKey.create(ClockTimeMarkers.ROOT_ID, Identifier.parse(markerId)));
    }

    /** The game's own period count and ticks in the period for a timeline, from its clock now. */
    static long[] gamePeriods(ServerLevel level, String timelineId) {
        MinecraftServer server = level.getServer();
        Timeline timeline = server.registryAccess().lookupOrThrow(Registries.TIMELINE)
                .getOrThrow(ResourceKey.create(Registries.TIMELINE, Identifier.parse(timelineId))).value();
        return new long[] {timeline.getPeriodCount(server.clockManager()), timeline.getCurrentTicks(server.clockManager())};
    }

    private static Holder<WorldClock> holder(MinecraftServer server, ClockId clock) {
        return server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                .getOrThrow(ResourceKey.create(Registries.WORLD_CLOCK, Identifier.parse(clock.id())));
    }

    // ---------------------------------------------------------------------------------------------------- clients

    /** What the game sends a client now: its own time packet, written to a buffer and read back, as a {@link ClockSync}. */
    static ClockSync delivered(ServerLevel level, ClockPort port, boolean rule) {
        MinecraftServer server = level.getServer();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ClientboundSetTimePacket.STREAM_CODEC.encode(buffer, server.clockManager().createFullSyncPacket());
        ClientboundSetTimePacket packet = ClientboundSetTimePacket.STREAM_CODEC.decode(buffer);
        Map<ClockId, NetworkState> clocks = new LinkedHashMap<>();
        for (Map.Entry<Holder<WorldClock>, ClockNetworkState> e : packet.clockUpdates().entrySet()) {
            ClockNetworkState s = e.getValue();
            clocks.put(new ClockId(e.getKey().unwrapKey().orElseThrow().identifier().toString()),
                    new NetworkState(s.totalTicks(), s.partialTick(), s.rate()));
        }
        return new ClockSync(packet.gameTime(), clocks);
    }

    /** The game's own client formula over a delivered sync, ticked to a game time. */
    interface RealClient {
        NetworkState read(ClockId clock, long gameTime);
    }

    /**
     * The real {@code ClientClockManager}, fed what was delivered, where the game's client classes are present (a dedicated
     * server has none, and then there is nothing to compare, so this is null). It is reached by reflection because the
     * variant is linked against the server's classes.
     */
    static RealClient realClient(ServerLevel level, ClockSync delivered) {
        try {
            Class<?> type = Class.forName("net.minecraft.client.ClientClockManager");
            Object manager = type.getConstructor().newInstance();
            Map<Holder<WorldClock>, ClockNetworkState> updates = new LinkedHashMap<>();
            for (Map.Entry<ClockId, NetworkState> e : delivered.clocks().entrySet()) {
                NetworkState s = e.getValue();
                updates.put(holder(level.getServer(), e.getKey()), new ClockNetworkState(s.totalTicks(), s.partialTick(), s.rate()));
            }
            type.getMethod("handleUpdates", long.class, Map.class).invoke(manager, delivered.gameTime(), updates);
            Method tick = type.getMethod("tick", long.class);
            Method instance = type.getMethod("getInstance", Holder.class);
            Method[] reads = new Method[3];
            return (clock, gameTime) -> {
                try {
                    tick.invoke(manager, gameTime);
                    Object i = instance.invoke(manager, holder(level.getServer(), clock));
                    if (reads[0] == null) {
                        for (int k = 0; k < 3; k++) {
                            reads[k] = i.getClass().getMethod(new String[] {"totalTicks", "partialTick", "rate"}[k]);
                            reads[k].setAccessible(true);
                        }
                    }
                    return new NetworkState((Long) reads[0].invoke(i), (Float) reads[1].invoke(i), (Float) reads[2].invoke(i));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("world-clock conformance: the game's client did not answer: " + e);
                }
            };
        } catch (ReflectiveOperationException | LinkageError absent) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------------- this variant's own cases

    static List<ClockConformance.Case> extraCases() {
        List<ClockConformance.Case> cases = new ArrayList<>();
        cases.add(new ClockConformance.Case("declaration_agrees_with_the_game", level -> declarationAgrees((ServerLevel) level)));
        cases.add(new ClockConformance.Case("datapack_text_is_generated", level -> datapackText()));
        cases.add(new ClockConformance.Case("save_and_reload", level -> saveAndReload((ServerLevel) level)));
        return cases;
    }

    /** The game's registered clocks and timelines are the declaration's: the conformance data is in this mod's resources. */
    private static void declarationAgrees(ServerLevel level) {
        List<String> problems = WorldClocks.of(level.getServer(), ClockConformance.WORLD).problems();
        ClockConformance.expect("the game's data agrees with the declaration (the conformance data is data/" + ClockConformance.NAMESPACE
                + "/world_clock/calendar.json and timeline/calendar.json in this mod's resources): " + problems, problems.isEmpty());
    }

    /** {@link ClockDatapack} writes exactly the files the game was given. */
    private static void datapackText() {
        Map<String, String> files = ClockDatapack.files(ClockConformance.WORLD, ClockConformance.NAMESPACE);
        ClockConformance.expect("the conformance world has a clock and a timeline to write: " + files.keySet(), files.size() == 2);
        for (Map.Entry<String, String> f : files.entrySet()) {
            try (InputStream in = ClockConformance.class.getResourceAsStream("/" + f.getKey())) {
                ClockConformance.expect(f.getKey() + " is missing from this mod's resources", in != null);
                String shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                ClockConformance.expect(f.getKey() + " differs from what ClockDatapack writes:\n" + shipped + "\nagainst\n" + f.getValue(),
                        shipped.equals(f.getValue()));
            } catch (java.io.IOException e) {
                throw new AssertionError("world-clock conformance: could not read " + f.getKey() + ": " + e);
            }
        }
    }

    /** The game's own saved form of its clocks ({@code PackedClockStates.CODEC}) keeps every field of a clock exactly, and reading it gives them back. */
    private static void saveAndReload(ServerLevel level) {
        ClockPort port = WorldClocks.of(level.getServer(), ClockConformance.WORLD);
        ClockId calendar = ClockConformance.CALENDAR;
        ClockState was = port.state(calendar);
        try {
            port.apply(calendar, new ClockCommand.SetRate(0.3F));
            port.apply(calendar, new ClockCommand.SetTicks(4321L));
            ClockState stateOf;
            // a fraction the codec must keep exactly: tick a few times with the rule on
            boolean rule = ruleOn(level);
            setRule(level, true);
            try {
                isolate(port, calendar, () -> {
                    for (int i = 0; i < 7; i++) {
                        port.tick(true);
                    }
                });
            } finally {
                setRule(level, rule);
            }
            port.apply(calendar, new ClockCommand.Pause());
            stateOf = port.state(calendar);
            ClockConformance.expect("a state with a fraction, a rate and a pause to save: " + stateOf, stateOf.partialTick() != 0.0F && stateOf.paused());

            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
            PackedClockStates packed = level.getServer().clockManager().packState();
            DataResult<Tag> encoded = PackedClockStates.CODEC.encodeStart(ops, packed);
            Tag saved = encoded.getOrThrow();
            PackedClockStates loaded = PackedClockStates.CODEC.parse(ops, saved).getOrThrow();
            ClockConformance.expect("the game's saved clocks read back as they were", loaded.equals(packed));
            net.minecraft.world.clock.ClockState back = loaded.clocks().get(holder(level.getServer(), calendar));
            ClockConformance.expect("the reloaded conformance clock is the one the port had: " + back + " and " + stateOf,
                    back != null && back.totalTicks() == stateOf.totalTicks() && back.partialTick() == stateOf.partialTick()
                            && back.rate() == stateOf.rate() && back.paused() == stateOf.paused());
        } finally {
            port.apply(calendar, new ClockCommand.Resume());
            port.apply(calendar, new ClockCommand.SetRate(was.rate()));
            port.apply(calendar, new ClockCommand.SetTicks(was.totalTicks()));
            if (was.paused()) {
                port.apply(calendar, new ClockCommand.Pause());
            }
        }
    }
}
