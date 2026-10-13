package mycelium.bridges.world_clock.conformance;

import mycelium.world_clock.v1.model.Applied;
import mycelium.world_clock.v1.model.ClientClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.ReferenceClockModel;
import mycelium.world_clock.v1.model.StandardTimelines;
import mycelium.world_clock.v1.model.Timeline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Conformance for {@link ClockPort} on a real game (decision 0018, laws C1 to C5 of {@code world.clock@1}): the cases a
 * mod imports into its game tests. They hold the pure model to the running game: each case drives the game's clocks through
 * the bridge's port and compares every state with what {@link ReferenceClockModel} says the same commands and ticks give.
 * They are written against the port, not against a variant, so they apply to the bridge's port and to a mod's own override
 * alike ({@link #cases(Ports)}).
 *
 * <p>A case is a name and a body that throws {@link AssertionError} when a law fails. Registering them is the mod's: each
 * loader registers game tests its own way, so a mod runs them from whatever test it has, handing each the level it runs in:
 *
 * <pre>
 * for (ClockConformance.Case c : ClockConformance.cases()) {
 *     register("world_clock_" + c.name(), helper -&gt; c.body().run(helper.getLevel()));
 * }
 * // and, to see the real server loop carry the clocks (each runs its start, waits its ticks, runs its end):
 * for (ClockConformance.Timed t : ClockConformance.timed(level -&gt; myClocks(level.getServer()))) { ... }
 * </pre>
 *
 * <p>The cases declare one clock of their own, {@link #CALENDAR} (a 7000-tick period with markers), and move only it and the
 * overworld clock, which they put back (a case is one server tick long, so nothing else sees the change). On 26.1 and later a
 * clock is data: the bridge ships the two files that declare it ({@code data/mycelium_conformance/}) beside the cases, and
 * {@code bridges { conformance() }} adds them to the mod's resources. Where the game reads a case by its own code (the native
 * variant's period counts and marker checks, the time packet, the real client's formula), the case says which.
 */
public final class ClockConformance {
    private ClockConformance() {
    }

    /** The namespace of the clock and timeline the cases declare. */
    public static final String NAMESPACE = "mycelium_conformance";

    /** The conformance clock: seven thousand ticks to a period, not the day, so nothing in the game moves with it. */
    public static final ClockId CALENDAR = new ClockId(NAMESPACE + ":calendar");
    public static final String EARLY = NAMESPACE + ":early";
    public static final String LATE = NAMESPACE + ":late";
    public static final String TURN = NAMESPACE + ":turn";

    /** The world the cases declare: the vanilla clocks and timelines, and the {@link #CALENDAR} timeline of the same name. */
    public static final ClockWorld WORLD = ClockWorld.vanilla().with(calendar());

    private static final ClockId OVERWORLD = StandardTimelines.OVERWORLD;
    private static final ClockId END = StandardTimelines.THE_END;
    private static final ClockModel MODEL = new ReferenceClockModel();
    private static final float[] RATES = {1.0F, 0.5F, 0.25F, 1.5F, 2.0F, 0.1F, 0.3F, 3.7F, 20.0F};

    /** The conformance timeline: period 7000; {@code early} at 1500, {@code late} at 6000, {@code turn} at 0 (hidden from commands). */
    public static Timeline calendar() {
        return Timeline.on(CALENDAR, NAMESPACE + ":calendar", 7000)
                .marker(EARLY, 1500)
                .marker(LATE, 6000)
                .marker(TURN, 0, false)
                .build();
    }

    /** One case: what it is called and what it checks. */
    public record Case(String name, Body body) {
    }

    /** A case that needs the real server loop: {@code start} runs now, {@code end} after {@code ticks} server ticks. */
    public record Timed(String name, int ticks, Body start, Body end) {
    }

    @FunctionalInterface
    public interface Body {
        /** Throws {@link AssertionError} when a law fails. {@code level} is the level the game test runs in. */
        void run(Level level) throws Exception;
    }

    /** How a case gets the port under test: the bridge's, or a mod's own override. */
    @FunctionalInterface
    public interface Ports {
        /** A port over {@code level}'s game for {@code world}. */
        ClockPort open(ServerLevel level, ClockWorld world);
    }

    /** Puts the players a test makes to sleep, deeply, so the game itself skips the night. The mod supplies it (a bed, a mock player). */
    @FunctionalInterface
    public interface Sleeper {
        void toBed(ServerLevel level) throws Exception;
    }

    /** The cases, for the bridge's own port. */
    public static List<Case> cases() {
        return cases(Wire.ports());
    }

    /** The cases, for the port {@code ports} gives (a mod's override). The cases that exercise the bridge's own seams are left out then. */
    public static List<Case> cases(Ports ports) {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("advance_with_rate", level -> advanceWithRate(new Ctx(level, ports))));
        cases.add(new Case("standstill", level -> standstill(new Ctx(level, ports))));
        cases.add(new Case("set_and_add", level -> setAndAdd(new Ctx(level, ports))));
        cases.add(new Case("marker_moves", level -> markerMoves(new Ctx(level, ports))));
        cases.add(new Case("periods_and_edges", level -> periodsAndEdges(new Ctx(level, ports))));
        cases.add(new Case("overworld_is_the_games", level -> overworldIsTheGames(new Ctx(level, ports))));
        cases.add(new Case("sleep_moves_the_default_clock", level -> sleepMovesTheDefaultClock(new Ctx(level, ports))));
        cases.add(new Case("sync_agrees", level -> syncAgrees(new Ctx(level, ports))));
        if (ports == Wire.ports()) {
            cases.addAll(Wire.extraCases());
        }
        return cases;
    }

    /**
     * The cases that need the server to run: the clocks of {@code production} (the port the mod really uses, with the hooks
     * it really calls) must have followed the game over some ticks as the model says. On a game before 26.1 that is the proof
     * the mod wired the bridge's tick hook.
     */
    public static List<Timed> timed(Function<Level, ClockPort> production) {
        List<Timed> out = new ArrayList<>();
        Object[] started = new Object[1];
        out.add(new Timed("clocks_follow_the_loop", 40, level -> {
            ClockPort port = production.apply(level);
            Map<ClockId, ClockState> states = new LinkedHashMap<>();
            for (ClockId clock : port.world().clocks()) {
                states.put(clock, port.state(clock));
            }
            started[0] = new Object[] {port.gameTime(), states, Wire.ruleOn((ServerLevel) level)};
        }, level -> {
            ClockPort port = production.apply(level);
            Object[] was = (Object[]) started[0];
            expect("clocks follow the loop: the start was not run", was != null);
            long elapsed = port.gameTime() - (Long) was[0];
            boolean rule = Wire.ruleOn((ServerLevel) level);
            expect("clocks follow the loop: the advance rule changed during the test (" + was[2] + " then " + rule + ")", rule == (Boolean) was[2]);
            expect("clocks follow the loop: the game ran no ticks between start and end", elapsed > 0);
            @SuppressWarnings("unchecked")
            Map<ClockId, ClockState> before = (Map<ClockId, ClockState>) was[1];
            for (Map.Entry<ClockId, ClockState> e : before.entrySet()) {
                ClockState want = e.getValue();
                for (long i = 0; i < elapsed; i++) {
                    want = MODEL.tick(want, rule);
                }
                ClockState got = port.state(e.getKey());
                expect("clocks follow the loop: clock " + e.getKey() + " after " + elapsed + " ticks is " + got + ", the model says "
                        + want + " from " + e.getValue(), got.equals(want));
            }
        }));
        return out;
    }

    /**
     * The game's own sleep, end to end: {@code sleeper} lays players down, the server loop skips the night, and the default clock
     * must have landed where the model's sleep says, the mod's other clocks where they were. The overworld is put back after.
     */
    public static Timed sleeping(Function<Level, ClockPort> production, Sleeper sleeper) {
        Object[] started = new Object[1];
        return new Timed("sleep_skips_the_night", 140, level -> {
            ServerLevel server = (ServerLevel) level;
            ClockPort port = production.apply(level);
            Wire.setRule(server, true);
            long night = 13000L + 24000L * 3;
            port.apply(OVERWORLD, new ClockCommand.SetTicks(night));
            Optional<Marker> wake = port.world().marker(OVERWORLD, StandardTimelines.WAKE_UP_FROM_SLEEP);
            started[0] = new Object[] {night, MODEL.sleep(port.state(OVERWORLD), wake, true), port.state(OVERWORLD)};
            sleeper.toBed(server);
        }, level -> {
            ClockPort port = production.apply(level);
            Object[] was = (Object[]) started[0];
            long night = (Long) was[0];
            Move want = (Move) was[1];
            ClockState got = port.state(OVERWORLD);
            // the night skipped: the clock stands at the wake marker (it may have run a few ticks of the morning before this check)
            expect("sleep: the clock should have skipped the night from " + night + ", it is at " + got.totalTicks(), got.totalTicks() >= want.state().totalTicks()
                    && got.totalTicks() < want.state().totalTicks() + 200L);
            port.apply(OVERWORLD, new ClockCommand.SetTicks(night));
        });
    }

    // ---------------------------------------------------------------------------------------------------- the context

    /** A case's world: the level, the port under test, and what a case does around it. */
    static final class Ctx {
        final ServerLevel level;
        final Ports ports;

        Ctx(Level level, Ports ports) {
            this.level = (ServerLevel) level;
            this.ports = ports;
        }

        ClockPort open() {
            return ports.open(level, WORLD);
        }

        /** Runs {@code body} with the advance rule at {@code on}, and puts the rule back. */
        void withRule(boolean on, Runnable body) {
            boolean was = Wire.ruleOn(level);
            Wire.setRule(level, on);
            try {
                body.run();
            } finally {
                Wire.setRule(level, was);
            }
        }

        /** Runs {@code body}, then puts the overworld clock (ticks, pause, rate) back as it was, and the rule. */
        void restoringOverworld(ClockPort port, Runnable body) {
            ClockState was = port.state(OVERWORLD);
            boolean rule = Wire.ruleOn(level);
            try {
                body.run();
            } finally {
                Wire.setRule(level, rule);
                port.apply(OVERWORLD, new ClockCommand.SetTicks(was.totalTicks()));
                port.apply(OVERWORLD, was.paused() ? new ClockCommand.Pause() : new ClockCommand.Resume());
                if (!Wire.overworldRateFixed()) {
                    port.apply(OVERWORLD, new ClockCommand.SetRate(was.rate()));
                }
            }
        }
    }

    private static void start(ClockPort port, float rate, long ticks) {
        port.apply(CALENDAR, new ClockCommand.Resume());
        port.apply(CALENDAR, new ClockCommand.SetRate(rate));
        port.apply(CALENDAR, new ClockCommand.SetTicks(ticks));
    }

    // ---------------------------------------------------------------------------------------------------- C1

    /** C1: over n ticks at rate r, the clock grows as the model's does, the fraction carried, at every tick and every rate. */
    private static void advanceWithRate(Ctx c) {
        ClockPort port = c.open();
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            for (float rate : RATES) {
                start(port, rate, 0L);
                ClockState want = port.state(CALENDAR);
                expect("C1 a started clock is at tick 0 with no fraction at rate " + rate, want.totalTicks() == 0 && want.partialTick() == 0.0F
                        && want.rate() == rate && !want.paused());
                for (int i = 1; i <= 400; i++) {
                    port.tick(true);
                    want = MODEL.tick(want, true);
                    ClockState got = port.state(CALENDAR);
                    expect("C1 at rate " + rate + " after " + i + " ticks the game has " + got + ", the model says " + want, got.equals(want));
                }
            }
            start(port, 0.5F, 0L);
            for (int i = 0; i < 400; i++) {
                port.tick(true);
            }
            expect("C1 half a tick a tick is two hundred in four hundred", port.state(CALENDAR).totalTicks() == 200L);
            start(port, 1.0F, 0L);
            for (int i = 0; i < 400; i++) {
                port.tick(true);
            }
            expect("C1 rate one is four hundred in four hundred", port.state(CALENDAR).totalTicks() == 400L);
            // a long run at an inexact rate accumulates float by float, as the game does: the model must stay bit for bit with it
            start(port, 0.1F, 0L);
            ClockState want = port.state(CALENDAR);
            for (int i = 0; i < 12000; i++) {
                port.tick(true);
                want = MODEL.tick(want, true);
            }
            expect("C1 twelve thousand ticks at rate 0.1: the game has " + port.state(CALENDAR) + ", the model says " + want, port.state(CALENDAR).equals(want));
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    /** C1: a paused clock, and every clock with the rule off, stand still, the fraction included. */
    private static void standstill(Ctx c) {
        ClockPort port = c.open();
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            start(port, 0.75F, 100L);
            for (int i = 0; i < 3; i++) {
                port.tick(true);
            }
            ClockState running = port.state(CALENDAR);
            expect("C1 a clock at rate 0.75 has moved and holds a fraction", running.totalTicks() == 102L && running.partialTick() == 0.25F);
            port.apply(CALENDAR, new ClockCommand.Pause());
            for (int i = 0; i < 50; i++) {
                port.tick(true);
            }
            ClockState stopped = port.state(CALENDAR);
            expect("C1 a paused clock does not move, nor does its fraction: " + stopped, stopped.equals(running.withPaused(true)));
            port.apply(CALENDAR, new ClockCommand.Resume());
            expect("C1 resuming changes only the pause", port.state(CALENDAR).equals(running));
            ClockState before = port.state(CALENDAR);
            c.withRule(false, () -> {
                for (int i = 0; i < 50; i++) {
                    port.tick(false);
                }
                expect("C1 with the rule off nothing moves: " + port.state(CALENDAR), port.state(CALENDAR).equals(before));
                NetworkState sent = port.sync(CALENDAR, false).clocks().get(CALENDAR);
                expect("C5 with the rule off a clock is sent still, rate zero: " + sent, sent.equals(MODEL.network(before, false)) && sent.rate() == 0.0F);
            });
            port.tick(true);
            expect("C1 the rule on again, the clock runs on", port.state(CALENDAR).equals(MODEL.tick(before, true)));
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    // ---------------------------------------------------------------------------------------------------- C2

    /** C2: set and add do what they say and nothing else, as the model does them. */
    private static void setAndAdd(Ctx c) {
        ClockPort port = c.open();
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            start(port, 0.5F, 1000L);
            port.tick(true);
            ClockState held = port.state(CALENDAR);
            expect("C2 a half-rate clock holds a fraction after one tick: " + held, held.totalTicks() == 1000L && held.partialTick() == 0.5F);
            applied(port, "add", new ClockCommand.AddTicks(250));
            ClockState added = port.state(CALENDAR);
            expect("C2 adding keeps the fraction: " + added, added.totalTicks() == 1250L && added.partialTick() == 0.5F);
            applied(port, "add below zero", new ClockCommand.AddTicks(-100000));
            ClockState floor = port.state(CALENDAR);
            expect("C2 adding below zero stops at zero and keeps the fraction: " + floor, floor.totalTicks() == 0L && floor.partialTick() == 0.5F);
            applied(port, "set", new ClockCommand.SetTicks(123456L));
            ClockState set = port.state(CALENDAR);
            expect("C2 setting lands on the tick with the fraction dropped: " + set, set.totalTicks() == 123456L && set.partialTick() == 0.0F
                    && set.rate() == 0.5F && !set.paused());
            applied(port, "rate", new ClockCommand.SetRate(2.0F));
            applied(port, "pause", new ClockCommand.Pause());
            applied(port, "resume", new ClockCommand.Resume());
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    /** Applies a command and holds the game's answer to the model's, for the state and the move. */
    private static void applied(ClockPort port, String what, ClockCommand command) {
        ClockState before = port.state(CALENDAR);
        Applied want = MODEL.apply(before, command, id -> WORLD.marker(CALENDAR, id));
        Applied got = port.apply(CALENDAR, command);
        expect("C2 " + what + " from " + before + ": the game answers " + got + ", the model " + want, got.equals(want));
        expect("C2 " + what + ": the clock is at the answered state", port.state(CALENDAR).equals(got.state()));
    }

    /** C2: moving to a marker lands on its next occurrence, forward, a clock on the marker is not moved, an absent marker changes nothing. */
    private static void markerMoves(Ctx c) {
        ClockPort port = c.open();
        long[] totals = {0, 1, 1499, 1500, 1501, 5999, 6000, 6001, 6999, 7000, 7001, 13999, 14000, 14001, 20000, 99999};
        List<String> ids = new ArrayList<>();
        for (Marker m : WORLD.timelinesOn(CALENDAR).get(0).markers()) {
            ids.add(m.id());
        }
        ids.add(NAMESPACE + ":no_such_marker");
        ids.add("minecraft:noon");
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            for (long total : totals) {
                for (String id : ids) {
                    start(port, 0.5F, total);
                    port.tick(true);
                    ClockState before = port.state(CALENDAR);
                    Applied want = MODEL.apply(before, new ClockCommand.MoveToMarker(id), m -> WORLD.marker(CALENDAR, m));
                    Applied got = port.apply(CALENDAR, new ClockCommand.MoveToMarker(id));
                    expect("C2 moving to " + id + " from " + before + ": the game answers " + got + ", the model " + want, got.equals(want));
                    if (got.move().get() == MoveResult.MOVED) {
                        expect("C2 a move lands clean, rate kept: " + got.state(), got.state().partialTick() == 0.0F && got.state().rate() == 0.5F
                                && got.state().totalTicks() > before.totalTicks());
                    } else {
                        expect("C2 a move that does not happen changes nothing: " + got.state(), got.state().equals(before));
                    }
                }
            }
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    // ---------------------------------------------------------------------------------------------------- C3

    /** C3: a mod's clock with its own period: periods recompose the total, and the edges between samples are the marker's occurrences, each once. */
    private static void periodsAndEdges(Ctx c) {
        ClockPort port = c.open();
        Timeline calendar = WORLD.timelinesOn(CALENDAR).get(0);
        List<Marker> markers = calendar.markers();
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            start(port, 1.0F, 6990L);
            long first = port.state(CALENDAR).totalTicks();
            long[] edges = new long[markers.size()];
            long total = first;
            for (int step = 0; step < 17000; step++) {
                port.tick(true);
                long now = port.state(CALENDAR).totalTicks();
                for (int m = 0; m < markers.size(); m++) {
                    long e = MODEL.edgesBetween(markers.get(m), total, now);
                    edges[m] += e;
                    expect("C3 a single tick crosses a marker exactly when it occurs: " + markers.get(m).id() + " at " + now,
                            (e == 1L) == MODEL.occursAt(markers.get(m), now));
                    Boolean game = Wire.gameAtMarker(c.level, CALENDAR, markers.get(m).id());
                    expect("C3 the game's own isAtTimeMarker agrees with the model for " + markers.get(m).id() + " at " + now,
                            game == null || game == MODEL.occursAt(markers.get(m), now));
                }
                if (step % 997 == 0) {
                    recomposes(c.level, calendar, now);
                }
                total = now;
            }
            for (int m = 0; m < markers.size(); m++) {
                long want = MODEL.repetitionCount(markers.get(m), total) - MODEL.repetitionCount(markers.get(m), first);
                expect("C3 the edges of " + markers.get(m).id() + " over " + (total - first) + " ticks are its occurrences: " + edges[m] + ", not " + want,
                        edges[m] == want);
                expect("C3 two and a half periods hold two or three of each marker: " + markers.get(m).id() + " " + edges[m], edges[m] >= 2L && edges[m] <= 3L);
            }
            // sparse samples: a jump over many periods still counts each occurrence once
            long before = port.state(CALENDAR).totalTicks();
            port.apply(CALENDAR, new ClockCommand.AddTicks(7000 * 3 + 10));
            long after = port.state(CALENDAR).totalTicks();
            for (Marker m : markers) {
                long counted = 0;
                for (long k = 0; k < 10; k++) {
                    long at = m.ticks() + k * 7000L;
                    if (at > before && at <= after) {
                        counted++;
                    }
                }
                expect("C3 over a jump of " + (after - before) + " ticks " + m.id() + " occurs " + counted + " times, the model counts "
                        + MODEL.edgesBetween(m, before, after), MODEL.edgesBetween(m, before, after) == counted);
            }
            recomposes(c.level, calendar, after);
            // a fast clock steps over several ticks at a time; the edges still add up
            start(port, 20.0F, 0L);
            long fastEdges = 0;
            long last = 0;
            for (int step = 0; step < 2000; step++) {
                port.tick(true);
                long now = port.state(CALENDAR).totalTicks();
                fastEdges += MODEL.edgesBetween(markers.get(0), last, now);
                last = now;
            }
            expect("C3 at rate twenty the edges of " + markers.get(0).id() + " over " + last + " ticks are its occurrences",
                    fastEdges == MODEL.repetitionCount(markers.get(0), last));
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    private static void recomposes(ServerLevel level, Timeline calendar, long total) {
        expect("C3 periods and ticks in the period recompose " + total, MODEL.periodCount(calendar, total) * 7000L + MODEL.currentTicks(calendar, total) == total);
        long[] game = Wire.gamePeriods(level, calendar.id());
        expect("C3 the game's own period count and ticks agree with the model at " + total, game == null
                || (game[0] == MODEL.periodCount(calendar, total) && game[1] == MODEL.currentTicks(calendar, total)));
    }

    // ---------------------------------------------------------------------------------------------------- C4

    /** The overworld clock is the game's: what the port reads is what the game reads, and a command lands where the game reads it. */
    private static void overworldIsTheGames(Ctx c) {
        ClockPort port = c.open();
        c.restoringOverworld(port, () -> {
            expect("the overworld the port reads is the game's: " + port.state(OVERWORLD).totalTicks() + " and " + Wire.gameOverworld(c.level),
                    port.state(OVERWORLD).totalTicks() == Wire.gameOverworld(c.level));
            for (long t : new long[] {0L, 5000L, 23999L, 24000L, 123456L}) {
                Applied a = port.apply(OVERWORLD, new ClockCommand.SetTicks(t));
                expect("a set lands where the game reads it: " + t + ", the game has " + Wire.gameOverworld(c.level),
                        Wire.gameOverworld(c.level) == t && a.state().totalTicks() == t && port.state(OVERWORLD).totalTicks() == t);
            }
            Wire.gameSetOverworld(c.level, 777L);
            expect("a set by the game is read by the port: " + port.state(OVERWORLD), port.state(OVERWORLD).totalTicks() == 777L);
            port.apply(OVERWORLD, new ClockCommand.AddTicks(23));
            expect("an add lands in the game: " + Wire.gameOverworld(c.level), Wire.gameOverworld(c.level) == 800L);
            port.apply(OVERWORLD, new ClockCommand.AddTicks(-5000));
            expect("an add below zero stops at zero in the game: " + Wire.gameOverworld(c.level), Wire.gameOverworld(c.level) == 0L);

            port.apply(OVERWORLD, new ClockCommand.Pause());
            expect("a paused overworld reads paused", port.state(OVERWORLD).paused());
            port.apply(OVERWORLD, new ClockCommand.Resume());
            expect("a resumed overworld reads running", !port.state(OVERWORLD).paused());

            if (Wire.overworldRateFixed()) {
                boolean refused = false;
                try {
                    port.apply(OVERWORLD, new ClockCommand.SetRate(2.0F));
                } catch (UnsupportedOperationException honest) {
                    refused = true;
                }
                expect("a game with one clock refuses another rate for it (honest limit) and keeps rate one: " + port.state(OVERWORLD),
                        refused && port.state(OVERWORLD).rate() == 1.0F);
            } else {
                port.apply(OVERWORLD, new ClockCommand.SetRate(2.0F));
                expect("the overworld takes a rate: " + port.state(OVERWORLD), port.state(OVERWORLD).rate() == 2.0F);
            }

            // the game's own markers: vanilla's day is at 1000 of 24000 (the model's StandardTimelines reads it)
            for (long t : new long[] {0L, 999L, 1000L, 1001L, 13000L, 23999L, 24000L, 25500L}) {
                port.apply(OVERWORLD, new ClockCommand.SetTicks(t));
                ClockState before = port.state(OVERWORLD);
                for (String id : new String[] {"minecraft:day", "minecraft:noon", "minecraft:night", "minecraft:midnight", "minecraft:wake_up_from_sleep"}) {
                    port.apply(OVERWORLD, new ClockCommand.SetTicks(t));
                    Applied want = MODEL.apply(port.state(OVERWORLD), new ClockCommand.MoveToMarker(id), m -> WORLD.marker(OVERWORLD, m));
                    Applied got = port.apply(OVERWORLD, new ClockCommand.MoveToMarker(id));
                    expect("moving the overworld from " + before + " to " + id + ": the game answers " + got + ", the model " + want,
                            got.move().equals(want.move()) && got.state().totalTicks() == want.state().totalTicks());
                    expect("and the game reads it there: " + Wire.gameOverworld(c.level), Wire.gameOverworld(c.level) == want.state().totalTicks());
                }
            }
        });
    }

    /** C4: sleeping moves the dimension's default clock to its wake marker, only that clock, and only with the rule on. */
    private static void sleepMovesTheDefaultClock(Ctx c) {
        ClockPort port = c.open();
        c.restoringOverworld(port, () -> {
            c.withRule(true, () -> {
                Optional<Marker> wake = WORLD.marker(OVERWORLD, StandardTimelines.WAKE_UP_FROM_SLEEP);
                for (long t : new long[] {0L, 1L, 12999L, 13000L, 17999L, 18000L, 23999L, 24000L, 24001L, 47999L, 48000L}) {
                    port.apply(OVERWORLD, new ClockCommand.SetTicks(t));
                    start(port, 0.5F, 3000L);
                    ClockState calendar = port.state(CALENDAR);
                    ClockState end = port.state(END);
                    ClockState before = port.state(OVERWORLD);
                    Move want = MODEL.sleep(before, wake, true);
                    Optional<Applied> got = port.sleep("minecraft:overworld", true);
                    expect("C4 sleeping at " + t + ": the game answers " + got + ", the model " + want,
                            got.isPresent() && got.get().state().equals(want.state()) && got.get().move().equals(Optional.of(want.result())));
                    long next = (t / 24000L + 1L) * 24000L;
                    if (t % 24000L == 0L) {
                        expect("C4 sleeping exactly at the wake marker does not move (the game's, and so the model's, one tick of difference from the old skip)",
                                want.result() == MoveResult.NOT_MOVED && port.state(OVERWORLD).totalTicks() == t);
                    } else {
                        expect("C4 sleeping at " + t + " lands on the next multiple of 24000, " + next + ": " + port.state(OVERWORLD),
                                want.result() == MoveResult.MOVED && port.state(OVERWORLD).totalTicks() == next);
                    }
                    expect("C4 the game reads the overworld where sleep left it", Wire.gameOverworld(c.level) == port.state(OVERWORLD).totalTicks());
                    expect("C4 sleeping moves the dimension's default clock only", port.state(CALENDAR).equals(calendar) && port.state(END).equals(end));
                }
                // a dimension with no clock does not skip; a clock without the wake marker does not skip
                expect("C4 the nether has no clock to sleep by", port.sleep("minecraft:the_nether", true).isEmpty());
                port.apply(OVERWORLD, new ClockCommand.SetTicks(13000L));
                Optional<Applied> theEnd = port.sleep("minecraft:the_end", true);
                expect("C4 the end's clock has no wake marker and does not skip: " + theEnd, theEnd.isPresent()
                        && theEnd.get().move().equals(Optional.of(MoveResult.NO_SUCH_MARKER)) && port.state(OVERWORLD).totalTicks() == 13000L);
            });
            // with the rule off nobody skips
            c.withRule(false, () -> {
                port.apply(OVERWORLD, new ClockCommand.SetTicks(13000L));
                Optional<Applied> off = port.sleep("minecraft:overworld", false);
                expect("C4 sleeping with the rule off does not skip: " + off, off.isPresent() && off.get().move().equals(Optional.of(MoveResult.NOT_MOVED))
                        && port.state(OVERWORLD).totalTicks() == 13000L);
            });
        });
    }

    // ---------------------------------------------------------------------------------------------------- C5

    /** C5: what clients are sent is the model's network state, and a client that follows it reads the server's ticks. */
    private static void syncAgrees(Ctx c) {
        ClockPort port = c.open();
        c.withRule(true, () -> Wire.isolate(port, CALENDAR, () -> {
            for (float rate : new float[] {1.0F, 0.5F, 0.1F, 2.75F}) {
                start(port, rate, 5000L);
                for (int i = 0; i < 7; i++) {
                    port.tick(true);
                }
                ClockState state = port.state(CALENDAR);
                NetworkState want = MODEL.network(state, true);
                ClockSync delivered = Wire.delivered(c.level, port, true);
                NetworkState got = delivered.clocks().get(CALENDAR);
                expect("C5 at rate " + rate + " the game tells clients " + got + ", the model " + want, want.equals(got));
                ClockSync reported = port.sync(true);
                expect("C5 the port reports what clients are told: " + reported.clocks().get(CALENDAR), want.equals(reported.clocks().get(CALENDAR))
                        && reported.gameTime() == port.gameTime());
                expect("C5 one clock's sync is that clock's: " + port.sync(CALENDAR, true).clocks(), port.sync(CALENDAR, true).clocks().equals(Map.of(CALENDAR, want)));

                // a client that follows it, tick by tick, reads the server's ticks; the real client too, where there is one
                boolean exact = rate == 1.0F || rate == 0.5F || rate == 2.75F;
                ClientClocks client = new ClientClocks(MODEL);
                client.handle(delivered);
                Wire.RealClient real = Wire.realClient(c.level, delivered);
                long g = delivered.gameTime();
                for (int i = 1; i <= 1000; i++) {
                    port.tick(true);
                    client.tick(g + i);
                    long server = port.state(CALENDAR).totalTicks();
                    long modelled = client.read(CALENDAR).totalTicks();
                    long drift = Math.abs(server - modelled);
                    if (exact ? drift != 0 : drift > 1) {
                        throw new AssertionError("world-clock conformance: C5 at rate " + rate + " after " + i + " ticks a client reads " + modelled
                                + ", the server has " + server + (exact ? " (exact where the rate's float arithmetic is exact)" : " (within one tick)"));
                    }
                    if (real != null) {
                        NetworkState theirs = real.read(CALENDAR, g + i);
                        expect("C5 the game's own client reads what the model's client does after " + i + " ticks at rate " + rate + ": " + theirs + " and "
                                + client.read(CALENDAR), theirs.equals(client.read(CALENDAR)));
                    }
                }
            }
            // paused, or the rule off: sent still
            start(port, 2.0F, 800L);
            port.apply(CALENDAR, new ClockCommand.Pause());
            NetworkState paused = Wire.delivered(c.level, port, true).clocks().get(CALENDAR);
            expect("C5 a paused clock is sent with rate zero: " + paused, paused.rate() == 0.0F && paused.totalTicks() == 800L);
            port.apply(CALENDAR, new ClockCommand.Resume());
            c.withRule(false, () -> {
                NetworkState ruleOff = Wire.delivered(c.level, port, false).clocks().get(CALENDAR);
                expect("C5 with the rule off a clock is sent with rate zero: " + ruleOff, ruleOff.rate() == 0.0F && ruleOff.totalTicks() == 800L);
            });
            port.apply(CALENDAR, new ClockCommand.SetRate(1.0F));
            port.apply(CALENDAR, new ClockCommand.SetTicks(0L));
        }));
    }

    // ---------------------------------------------------------------------------------------------------- checks

    static void expect(String what, boolean holds) {
        if (!holds) {
            throw new AssertionError("world-clock conformance: " + what);
        }
    }
}
