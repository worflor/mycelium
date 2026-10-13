package mycelium.bridges.world_clock.conformance;

import com.mojang.serialization.DataResult;
import mycelium.bridges.world_clock.ClockDatapack;
import mycelium.bridges.world_clock.WorldClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.PackedClockStates;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The conformance cases that need the bridge's own source (decision 0016): the entry point that builds the bridge's own port
 * ({@link #cases()}, what a mod that takes the bridge registers) and the cases of this variant's internals. Everything else is
 * {@link ClockCases}, written against the port, which a mod that overrides the bridge imports and calls with its own
 * {@link ClockCases.Ports}; this class is not imported for it, since it mentions what the override replaces.
 */
public final class ClockConformance extends ClockCases {
    private static final Ports PORTS = (level, world) -> WorldClocks.of(level.getServer(), world);

    private ClockConformance() {
    }

    /** The cases, for the bridge's own port, with the cases of its own seams. */
    public static List<Case> cases() {
        List<Case> cases = new ArrayList<>(cases(PORTS));
        cases.addAll(extraCases());
        return cases;
    }

    // ---------------------------------------------------------------------------------------------------- this variant's own cases

    private static List<Case> extraCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("declaration_agrees_with_the_game", level -> declarationAgrees((ServerLevel) level)));
        cases.add(new Case("datapack_text_is_generated", level -> datapackText()));
        cases.add(new Case("save_and_reload", level -> saveAndReload((ServerLevel) level)));
        return cases;
    }

    /** The game's registered clocks and timelines are the declaration's: the conformance data is in this mod's resources. */
    private static void declarationAgrees(ServerLevel level) {
        List<String> problems = WorldClocks.of(level.getServer(), ClockCases.WORLD).problems();
        ClockCases.expect("the game's data agrees with the declaration (the conformance data is data/" + ClockCases.NAMESPACE
                + "/world_clock/calendar.json and timeline/calendar.json in this mod's resources): " + problems, problems.isEmpty());
    }

    /** {@link ClockDatapack} writes exactly the files the game was given. */
    private static void datapackText() {
        Map<String, String> files = ClockDatapack.files(ClockCases.WORLD, ClockCases.NAMESPACE);
        ClockCases.expect("the conformance world has a clock and a timeline to write: " + files.keySet(), files.size() == 2);
        for (Map.Entry<String, String> f : files.entrySet()) {
            try (InputStream in = ClockCases.class.getResourceAsStream("/" + f.getKey())) {
                ClockCases.expect(f.getKey() + " is missing from this mod's resources", in != null);
                String shipped = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                ClockCases.expect(f.getKey() + " differs from what ClockDatapack writes:\n" + shipped + "\nagainst\n" + f.getValue(),
                        shipped.equals(f.getValue()));
            } catch (java.io.IOException e) {
                throw new AssertionError("world-clock conformance: could not read " + f.getKey() + ": " + e);
            }
        }
    }

    /** The game's own saved form of its clocks ({@code PackedClockStates.CODEC}) keeps every field of a clock exactly, and reading it gives them back. */
    private static void saveAndReload(ServerLevel level) {
        ClockPort port = WorldClocks.of(level.getServer(), ClockCases.WORLD);
        ClockId calendar = ClockCases.CALENDAR;
        ClockState was = port.state(calendar);
        try {
            port.apply(calendar, new ClockCommand.SetRate(0.3F));
            port.apply(calendar, new ClockCommand.SetTicks(4321L));
            ClockState stateOf;
            // a fraction the codec must keep exactly: tick a few times with the rule on
            boolean rule = Wire.ruleOn(level);
            Wire.setRule(level, true);
            try {
                Wire.isolate(port, calendar, () -> {
                    for (int i = 0; i < 7; i++) {
                        port.tick(true);
                    }
                });
            } finally {
                Wire.setRule(level, rule);
            }
            port.apply(calendar, new ClockCommand.Pause());
            stateOf = port.state(calendar);
            ClockCases.expect("a state with a fraction, a rate and a pause to save: " + stateOf, stateOf.partialTick() != 0.0F && stateOf.paused());

            RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
            PackedClockStates packed = level.getServer().clockManager().packState();
            DataResult<Tag> encoded = PackedClockStates.CODEC.encodeStart(ops, packed);
            Tag saved = encoded.getOrThrow();
            PackedClockStates loaded = PackedClockStates.CODEC.parse(ops, saved).getOrThrow();
            ClockCases.expect("the game's saved clocks read back as they were", loaded.equals(packed));
            net.minecraft.world.clock.ClockState back = loaded.clocks().get(Wire.holder(level.getServer(), calendar));
            ClockCases.expect("the reloaded conformance clock is the one the port had: " + back + " and " + stateOf,
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
