package mycelium.world_clock.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.world_clock.v1.model.Applied;
import mycelium.world_clock.v1.model.ClientClocks;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.StandardTimelines;
import mycelium.world_clock.v1.model.Timeline;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * world.clock@1: the laws C1 to C6 of a clock written as 26.x writes it (SPEC.md). Run against a {@link ClockModel} and
 * against the {@link ClockPort} a factory builds over it, so the same laws can later be run against a bridge.
 */
public final class WorldClockContractTests {
    private static final ClockId OVERWORLD = StandardTimelines.OVERWORLD;
    private static final ClockId CALENDAR = new ClockId("mod:calendar");

    private final ClockModel m;
    private final BiFunction<ClockModel, ClockWorld, ClockPort> ports;

    public WorldClockContractTests(ClockModel model, BiFunction<ClockModel, ClockWorld, ClockPort> ports) {
        this.m = model;
        this.ports = ports;
    }

    public Check run() {
        Check c = new Check();
        c1Advance(c);
        c2Moves(c);
        c3Periods(c);
        c4OldGame(c);
        c5Sync(c);
        c6Determinism(c);
        return c;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Remembers the first thing a loop got wrong, so a broken implementation costs one line, not thousands. */
    private static final class Probe {
        private String first;

        void expect(boolean ok, Supplier<String> detail) {
            if (!ok && first == null) {
                first = detail.get();
            }
        }

        void report(Check c, String label) {
            c.equal("ok", first == null ? "ok" : first, label);
        }
    }

    private static Marker markerOn(int period, int ticks) {
        return Timeline.on(OVERWORLD, "t", period).marker("m", ticks).build().marker("m").get();
    }

    private static Marker dayMarker(String id) {
        return StandardTimelines.day().marker(id).get();
    }

    private static boolean occurs(Marker mk, long t) {
        return mk.periodic() ? t % mk.periodTicks().getAsInt() == mk.ticks() : t == mk.ticks();
    }

    private ClockState run(ClockState s, int ticks, boolean advanceTime) {
        for (int i = 0; i < ticks; i++) {
            s = m.tick(s, advanceTime);
        }
        return s;
    }

    private static boolean same(ClockState a, ClockState b) {
        return a.equals(b);
    }

    // ------------------------------------------------------------------------------------------------ C1

    /** C1: over n ticks at rate r, not paused, the total grows by the floor of the accumulated n times r, the fraction carried. */
    private void c1Advance(Check c) {
        for (float rate : new float[] {1.0F, 2.0F, 3.0F}) {
            ClockState s = run(new ClockState(100L, 0.0F, rate, false), 10, true);
            c.equalLong(100L + (long) (10 * rate), s.totalTicks(), "C1:integer-rate-advances");
            c.that(s.partialTick() == 0.0F, "C1:integer-rate-advances");
        }

        ClockState half = run(new ClockState(0L, 0.0F, 0.5F, false), 10, true);
        c.equalLong(5L, half.totalTicks(), "C1:fraction-carries");
        c.that(half.partialTick() == 0.0F, "C1:fraction-carries");
        half = m.tick(half, true);
        c.equalLong(5L, half.totalTicks(), "C1:fraction-carries");
        c.that(half.partialTick() == 0.5F, "C1:fraction-carries");
        ClockState quarter = run(new ClockState(0L, 0.0F, 0.25F, false), 4, true);
        c.equalLong(1L, quarter.totalTicks(), "C1:fraction-carries");
        ClockState oneAndHalf = run(new ClockState(0L, 0.0F, 1.5F, false), 3, true);
        c.equalLong(4L, oneAndHalf.totalTicks(), "C1:fraction-carries");
        c.that(oneAndHalf.partialTick() == 0.5F, "C1:fraction-carries");

        ClockState pausedClock = new ClockState(777L, 0.75F, 1.5F, true);
        ClockState ruleOff = new ClockState(777L, 0.75F, 1.5F, false);
        c.that(same(pausedClock, run(pausedClock, 50, true)), "C1:standstill");
        c.that(same(ruleOff, run(ruleOff, 50, false)), "C1:standstill");
        c.that(same(pausedClock, run(pausedClock, 50, false)), "C1:standstill");

        // n ticks at rate r move the clock n times r, to the float's precision, whatever the start and the rate.
        Seeded r = new Seeded(0xC10C0L);
        Probe grows = new Probe();
        for (int i = 0; i < 200; i++) {
            float rate = (float) (r.nextDouble() * 8.0);
            long start = r.nextInt(1_000_000);
            float p0 = (float) (r.nextDouble() * 0.999);
            int n = 1 + r.nextInt(3000);
            ClockState s = run(new ClockState(start, p0, rate, false), n, true);
            double got = (double) (s.totalTicks() - start) + ((double) s.partialTick() - (double) p0);
            double want = (double) n * (double) rate;
            double tolerance = n * 1e-6 * (rate + 1.0) + 1e-6;
            final int at = i;
            grows.expect(Math.abs(got - want) <= tolerance && s.rate() == rate && !s.paused(),
                    () -> "case " + at + ": rate " + rate + " for " + n + " ticks moved " + got + ", wanted " + want);
        }
        grows.report(c, "C1:accumulates-n-times-rate");

        // Any sequence of ticks, pauses, rates, sets and adds tracks an exact accounting of the same commands.
        Probe tracks = new Probe();
        for (int seed = 0; seed < 40; seed++) {
            Seeded q = new Seeded(0xC10C1L + seed);
            ClockState s = ClockState.start();
            BigDecimal exact = BigDecimal.ZERO;
            float rate = 1.0F;
            boolean paused = false;
            for (int step = 0; step < 300; step++) {
                switch (q.nextInt(8)) {
                    case 0, 1, 2, 3 -> {
                        boolean advance = q.nextInt(5) != 0;
                        int k = 1 + q.nextInt(40);
                        for (int i = 0; i < k; i++) {
                            s = m.tick(s, advance);
                            if (advance && !paused) {
                                exact = exact.add(new BigDecimal((double) rate));
                            }
                        }
                    }
                    case 4 -> {
                        paused = !paused;
                        s = m.apply(s, paused ? new ClockCommand.Pause() : new ClockCommand.Resume(), id -> Optional.empty()).state();
                    }
                    case 5 -> {
                        rate = (float) (q.nextDouble() * 8.0);
                        s = m.apply(s, new ClockCommand.SetRate(rate), id -> Optional.empty()).state();
                    }
                    case 6 -> {
                        long value = q.nextInt(2_000_000);
                        s = m.apply(s, new ClockCommand.SetTicks(value), id -> Optional.empty()).state();
                        exact = new BigDecimal(value);
                    }
                    default -> {
                        int add = q.nextInt(5000);
                        s = m.apply(s, new ClockCommand.AddTicks(add), id -> Optional.empty()).state();
                        exact = exact.add(new BigDecimal(add));
                    }
                }
            }
            BigDecimal got = new BigDecimal(s.totalTicks()).add(new BigDecimal((double) s.partialTick()));
            final int at = seed;
            final BigDecimal want = exact;
            final ClockState end = s;
            tracks.expect(got.subtract(exact).abs().compareTo(new BigDecimal("0.01")) <= 0
                            && end.rate() == rate && end.paused() == paused,
                    () -> "sequence " + at + " ended at " + end + ", the exact accounting says " + want);
        }
        tracks.report(c, "C1:sequence-tracks-exact");
    }

    // ------------------------------------------------------------------------------------------------ C2

    /** C2: moving to a marker lands on its next occurrence, never earlier; a marker the clock lacks changes nothing. */
    private void c2Moves(Check c) {
        Probe forward = new Probe();
        Probe notMoved = new Probe();
        Probe clean = new Probe();
        for (int period : new int[] {1, 2, 3, 7, 10}) {
            for (int ticks = 0; ticks < period; ticks++) {
                Marker mk = markerOn(period, ticks);
                for (long total = 0; total <= 4L * period + 3; total++) {
                    ClockState s = new ClockState(total, 0.25F, 1.5F, true);
                    Move mv = m.moveTo(s, Optional.of(mk));
                    checkMove(mk, s, mv, forward, notMoved, clean);
                }
            }
        }
        Seeded r = new Seeded(0xC20C0L);
        List<Marker> day = StandardTimelines.day().markers();
        for (int i = 0; i < 2000; i++) {
            Marker mk = day.get(r.nextInt(day.size()));
            long total = r.nextInt(100) == 0
                    ? r.nextInt(50) * 24000L + mk.ticks()
                    : (long) r.nextInt(2_000_000_000);
            ClockState s = new ClockState(total, (float) (r.nextDouble() * 0.99), (float) (r.nextDouble() * 4), r.nextBoolean());
            checkMove(mk, s, m.moveTo(s, Optional.of(mk)), forward, notMoved, clean);
        }
        forward.report(c, "C2:moves-forward-only");
        notMoved.report(c, "C2:at-marker-not-moved");
        clean.report(c, "C2:lands-clean");

        // A marker the clock does not have is reported, and nothing changes.
        ClockState s = new ClockState(500L, 0.5F, 2.0F, true);
        Move none = m.moveTo(s, Optional.empty());
        c.equal(MoveResult.NO_SUCH_MARKER, none.result(), "C2:no-such-marker");
        c.that(same(s, none.state()), "C2:no-such-marker");
        Applied viaCommand = m.apply(s, new ClockCommand.MoveToMarker("nope"), id -> Optional.empty());
        c.equal(Optional.of(MoveResult.NO_SUCH_MARKER), viaCommand.move(), "C2:no-such-marker");
        c.that(same(s, viaCommand.state()), "C2:no-such-marker");

        // A marker with no period is a point in time: the clock goes to it, back in time if it has passed it. This is the
        // game's own behaviour (ClockTimeMarker.resolveTimeToMoveTo), and it is the one place a move goes backwards.
        Marker once = Timeline.on(OVERWORLD, "once", 0).marker("m", 500).build().marker("m").get();
        Move early = m.moveTo(new ClockState(100L, 0.5F, 1.0F, false), Optional.of(once));
        c.equal(MoveResult.MOVED, early.result(), "C2:one-shot-goes-to-its-tick");
        c.equalLong(500L, early.state().totalTicks(), "C2:one-shot-goes-to-its-tick");
        Move late = m.moveTo(new ClockState(900L, 0.5F, 1.0F, false), Optional.of(once));
        c.equal(MoveResult.MOVED, late.result(), "C2:one-shot-goes-to-its-tick");
        c.equalLong(500L, late.state().totalTicks(), "C2:one-shot-goes-to-its-tick");
        c.equal(MoveResult.NOT_MOVED, m.moveTo(new ClockState(500L, 0.0F, 1.0F, false), Optional.of(once)).result(),
                "C2:one-shot-goes-to-its-tick");

        // Set, add, pause, resume and rate do exactly what they say and touch nothing else.
        ClockState base = new ClockState(1000L, 0.5F, 2.0F, true);
        c.equal(new ClockState(50L, 0.0F, 2.0F, true), m.set(base, 50L), "C2:set-add-exact");
        c.equal(new ClockState(1025L, 0.5F, 2.0F, true), m.add(base, 25), "C2:set-add-exact");
        c.equal(new ClockState(0L, 0.5F, 2.0F, true), m.add(base, -5000), "C2:set-add-exact");
        c.equal(new ClockState(1000L, 0.5F, 2.0F, false), m.pause(base, false), "C2:set-add-exact");
        c.equal(new ClockState(1000L, 0.5F, 0.5F, true), m.rate(base, 0.5F), "C2:set-add-exact");
        c.equal(new ClockState(7L, 0.0F, 2.0F, true),
                m.apply(base, new ClockCommand.SetTicks(7L), id -> Optional.empty()).state(), "C2:set-add-exact");

        // A marker lies inside its period, and a clock's marker ids are its own.
        c.throwsAny(() -> Timeline.on(OVERWORLD, "bad", 100).marker("m", 100), "C2:marker-inside-period");
        c.throwsAny(() -> Timeline.on(OVERWORLD, "dup", 100).marker("m", 1).marker("m", 2), "C2:marker-ids-unique-per-clock");
        c.throwsAny(() -> ClockWorld.vanilla().with(Timeline.on(OVERWORLD, "other", 1000).marker("minecraft:noon", 5).build()),
                "C2:marker-ids-unique-per-clock");
    }

    private void checkMove(Marker mk, ClockState s, Move mv, Probe forward, Probe notMoved, Probe clean) {
        long total = s.totalTicks();
        if (occurs(mk, total)) {
            notMoved.expect(mv.result() == MoveResult.NOT_MOVED && same(s, mv.state()),
                    () -> "at " + total + " on a marker at " + mk.ticks() + "/" + mk.periodTicks() + ": " + mv);
            return;
        }
        int period = mk.periodTicks().getAsInt();
        long next = total - total % period + mk.ticks();
        if (next <= total) {
            next += period;
        }
        final long expected = next;
        forward.expect(mv.result() == MoveResult.MOVED && mv.state().totalTicks() == expected,
                () -> "from " + total + " to a marker at " + mk.ticks() + "/" + period + ": " + mv + ", wanted " + expected);
        clean.expect(mv.state().partialTick() == 0.0F && mv.state().rate() == s.rate() && mv.state().paused() == s.paused(),
                () -> "after moving from " + s + ": " + mv.state());
    }

    // ------------------------------------------------------------------------------------------------ C3

    /** C3: ticks in a period and the period count recompose the total; edges between two totals are the occurrences between them, each once. */
    private void c3Periods(Check c) {
        Seeded r = new Seeded(0xC30C0L);
        Probe recompose = new Probe();
        for (int i = 0; i < 2000; i++) {
            int period = 1 + r.nextInt(200_000);
            Timeline t = Timeline.on(OVERWORLD, "x", period).build();
            long total = (r.nextLong() >>> 24) % 1_000_000_000_000L;
            long count = m.periodCount(t, total);
            long in = m.currentTicks(t, total);
            recompose.expect(count * period + in == total && in >= 0 && in < period,
                    () -> "total " + total + " in a period of " + period + ": count " + count + ", ticks " + in);
        }
        recompose.report(c, "C3:periods-recompose");
        Timeline day = StandardTimelines.day();
        c.equalLong(5L, m.periodCount(day, 5L * 24000 + 13000), "C3:periods-recompose");
        c.equalLong(13000L, m.currentTicks(day, 5L * 24000 + 13000), "C3:periods-recompose");
        c.equalLong(0L, m.periodCount(day, 23999L), "C3:periods-recompose");
        c.equalLong(1L, m.periodCount(day, 24000L), "C3:periods-recompose");
        Timeline open = Timeline.on(OVERWORLD, "open", 0).build();
        c.equalLong(0L, m.periodCount(open, 123456L), "C3:non-periodic-timeline");
        c.equalLong(123456L, m.currentTicks(open, 123456L), "C3:non-periodic-timeline");

        // Occurrences, against counting them one by one.
        Probe count = new Probe();
        Probe occursAt = new Probe();
        Probe around = new Probe();
        for (int period = 1; period <= 9; period++) {
            for (int ticks = 0; ticks < period; ticks++) {
                Marker mk = markerOn(period, ticks);
                for (long t = 0; t <= 5L * period; t++) {
                    long expected = 0;
                    OptionalLong previous = OptionalLong.empty();
                    OptionalLong next = OptionalLong.empty();
                    for (long u = 0; u <= t + 2L * period; u++) {
                        if (u % period == ticks) {
                            if (u <= t) {
                                expected++;
                                previous = OptionalLong.of(u);
                            } else if (!next.isPresent()) {
                                next = OptionalLong.of(u);
                            }
                        }
                    }
                    final long at = t;
                    final long want = expected;
                    count.expect(m.repetitionCount(mk, t) == want,
                            () -> "marker " + mk.ticks() + "/" + mk.periodTicks() + " at " + at + ": " + m.repetitionCount(mk, at) + ", wanted " + want);
                    occursAt.expect(m.occursAt(mk, t) == (t % period == ticks), () -> "occursAt " + at + " for " + mk);
                    final OptionalLong wantPrev = previous;
                    final OptionalLong wantNext = next;
                    around.expect(m.previousOccurrence(mk, t).equals(wantPrev) && m.nextOccurrence(mk, t).equals(wantNext),
                            () -> "around " + at + " for " + mk.ticks() + "/" + mk.periodTicks() + ": "
                                    + m.previousOccurrence(mk, at) + " " + m.nextOccurrence(mk, at));
                }
            }
        }
        Marker once = Timeline.on(OVERWORLD, "once", 0).marker("m", 5).build().marker("m").get();
        for (long t = 0; t < 12; t++) {
            count.expect(m.repetitionCount(once, t) == (t >= 5 ? 1 : 0), () -> "one-shot count");
            occursAt.expect(m.occursAt(once, t) == (t == 5), () -> "one-shot occursAt");
            around.expect(m.nextOccurrence(once, t).equals(t < 5 ? OptionalLong.of(5) : OptionalLong.empty()),
                    () -> "one-shot next");
        }
        count.report(c, "C3:repetition-count");
        occursAt.report(c, "C3:occurs-at");
        around.report(c, "C3:previous-next-occurrence");

        // Edges: every occurrence once, however sparse the samples, none going back.
        Probe edges = new Probe();
        for (int seed = 0; seed < 60; seed++) {
            Seeded q = new Seeded(0xC30C1L + seed);
            int period = 1 + q.nextInt(50);
            Marker mk = markerOn(period, q.nextInt(period));
            long prev = q.nextInt(200);
            long first = prev;
            long forwardSum = 0;
            for (int step = 0; step < 40; step++) {
                long cur;
                switch (q.nextInt(5)) {
                    case 0 -> cur = prev;
                    case 1 -> cur = Math.max(0, prev - q.nextInt(120));
                    default -> cur = prev + q.nextInt(3 * period + 1);
                }
                long expected = 0;
                for (long u = prev + 1; u <= cur; u++) {
                    if (u % period == mk.ticks()) {
                        expected++;
                    }
                }
                final long a = prev;
                final long b = cur;
                final long want = expected;
                edges.expect(m.edgesBetween(mk, a, b) == want,
                        () -> "marker " + mk.ticks() + "/" + mk.periodTicks() + " between " + a + " and " + b + ": "
                                + m.edgesBetween(mk, a, b) + ", wanted " + want);
                if (cur > prev) {
                    forwardSum += expected;
                    prev = cur;
                } else {
                    prev = cur;
                }
            }
            edges.expect(forwardSum >= 0, () -> "sum");
            long jump = first + 5L * period;
            final long from = first;
            edges.expect(m.edgesBetween(mk, from, jump) == 5L, () -> "five periods apart must be five edges, got "
                    + m.edgesBetween(mk, from, jump));
        }
        Marker noon = dayMarker("minecraft:noon");
        edges.expect(m.edgesBetween(noon, 5999L, 6000L) == 1L, () -> "an occurrence exactly at the later sample counts there");
        edges.expect(m.edgesBetween(noon, 6000L, 6001L) == 0L, () -> "and not again at the next");
        edges.expect(m.edgesBetween(noon, 6000L, 6000L + 24000L * 3) == 3L, () -> "three days on");
        edges.report(c, "C3:edges-each-once");

        // data.catalog@1's dawn edge, as the edge of a marker at tick 0 of a 24000-tick day; vanilla's `day` marker is the same edge shifted by its 1000 ticks.
        Marker dawn = Timeline.on(OVERWORLD, "dawn", 24000).marker("dawn", 0).build().marker("dawn").get();
        Marker dayEdge = dayMarker("minecraft:day");
        Probe dawnProbe = new Probe();
        for (int seed = 0; seed < 40; seed++) {
            Seeded q = new Seeded(0xC30C2L + seed);
            PreTwentySix.DawnRule rule = new PreTwentySix.DawnRule();
            long prev = -1;
            for (int step = 0; step < 100; step++) {
                long cur;
                switch (q.nextInt(7)) {
                    case 0 -> cur = prev < 0 ? 0 : prev;
                    case 1 -> cur = prev <= 0 ? 0 : q.nextInt((int) Math.min(prev, Integer.MAX_VALUE));
                    case 2 -> cur = Math.max(prev, 0) + q.nextInt(5) * 24000L + q.nextInt(100);
                    default -> cur = Math.max(prev, 0) + q.nextInt(30000);
                }
                long observed = rule.observe(cur);
                if (prev >= 0) {
                    long edges1 = m.edgesBetween(dawn, prev, cur);
                    final long a = prev;
                    final long b = cur;
                    final long saw = observed;
                    dawnProbe.expect((edges1 > 0) == (observed >= 0),
                            () -> "between " + a + " and " + b + " the model counts " + edges1 + " dawns, sample observer saw " + saw);
                    if (edges1 > 0 && observed >= 0) {
                        long entered = m.previousOccurrence(dawn, cur).getAsLong() / 24000L;
                        dawnProbe.expect(entered == observed, () -> "the dawn entered at " + b + " is " + entered + ", sample observer said " + saw);
                    }
                    if (prev >= 1000) {
                        dawnProbe.expect(m.edgesBetween(dayEdge, prev, cur) == m.edgesBetween(dawn, prev - 1000, Math.max(cur - 1000, prev - 1000)),
                                () -> "the day marker's edge is the dawn edge shifted by 1000 ticks");
                    }
                }
                prev = cur;
            }
        }
        dawnProbe.report(c, "C3:dawn-is-the-day-edge");
    }

    // ------------------------------------------------------------------------------------------------ C4

    /** C4: one clock at rate one, a 24000 day and a 192000 moon is the game before 26.1: its day time, moon phase, day count and sleep skip. */
    private void c4OldGame(Check c) {
        Timeline day = StandardTimelines.day();
        Timeline moon = StandardTimelines.moon();
        Marker wake = day.marker(StandardTimelines.WAKE_UP_FROM_SLEEP).get();
        Seeded r = new Seeded(0xC40C0L);

        Probe ticks = new Probe();
        Probe sleeps = new Probe();
        Probe ruleOff = new Probe();
        Probe phases = new Probe();
        Probe days = new Probe();
        for (int i = 0; i < 3000; i++) {
            long t = (r.nextLong() >>> 20) % 1_000_000_000L;
            boolean daylight = r.nextInt(4) != 0;
            ClockState s = ClockState.at(t);
            ticks.expect(m.tick(s, daylight).totalTicks() == PreTwentySix.tickTime(t, daylight),
                    () -> "tick at " + t + " daylight " + daylight);
            if (t % 24000 != 0) {
                Move sleep = m.sleep(s, Optional.of(wake), true);
                sleeps.expect(sleep.state().totalTicks() == PreTwentySix.sleepSkip(t, true),
                        () -> "sleeping at " + t + " lands on " + sleep.state().totalTicks() + ", the old game on " + PreTwentySix.sleepSkip(t, true));
            }
            Move off = m.sleep(s, Optional.of(wake), false);
            ruleOff.expect(off.state().totalTicks() == PreTwentySix.sleepSkip(t, false) && off.state().totalTicks() == t,
                    () -> "sleeping at " + t + " with time not advancing moved the clock");
            phases.expect(m.phase(moon, t, StandardTimelines.MOON_PHASES) == PreTwentySix.moonPhase(t),
                    () -> "moon phase at " + t + ": " + m.phase(moon, t, StandardTimelines.MOON_PHASES) + ", the old game " + PreTwentySix.moonPhase(t));
            days.expect(m.periodCount(day, t) == PreTwentySix.dayCount(t) && m.currentTicks(day, t) == PreTwentySix.dayTimeOfDay(t),
                    () -> "day count or time of day at " + t);
        }
        for (long each = 1; each < 3 * 24000L; each++) {
            final long t = each;
            if (t % 24000 != 0) {
                sleeps.expect(m.sleep(ClockState.at(t), Optional.of(wake), true).state().totalTicks() == PreTwentySix.sleepSkip(t, true),
                        () -> "sleeping at " + t + " does not land on the next multiple of 24000");
            }
        }
        for (int k = 0; k < 20; k++) {
            phases.expect(m.phase(moon, k * 24000L, 8) == k % 8 && m.phase(moon, k * 24000L + 23999, 8) == k % 8,
                    () -> "the moon changes phase on the day, not before or after");
        }
        ticks.report(c, "C4:tick");
        sleeps.report(c, "C4:sleep-skip");
        ruleOff.report(c, "C4:rule-off-no-skip");
        phases.report(c, "C4:moon-phase");
        days.report(c, "C4:day-count");

        // The one tick where they differ, pinned: exactly at the wake marker the old game skips a whole day, 26.x does not move.
        ClockState onTheDot = ClockState.at(48000L);
        Move stay = m.sleep(onTheDot, Optional.of(wake), true);
        c.equal(MoveResult.NOT_MOVED, stay.result(), "C4:sleep-at-the-marker");
        c.equalLong(48000L, stay.state().totalTicks(), "C4:sleep-at-the-marker");
        c.equalLong(72000L, PreTwentySix.sleepSkip(48000L, true), "C4:sleep-at-the-marker");

        // A clock without the wake marker does not skip.
        Move noMarker = m.sleep(ClockState.at(14000L), Optional.empty(), true);
        c.equal(MoveResult.NO_SUCH_MARKER, noMarker.result(), "C4:no-wake-marker-no-skip");
        c.equalLong(14000L, noMarker.state().totalTicks(), "C4:no-wake-marker-no-skip");

        // Through the port: the whole old game as a script, then the dimensions.
        Probe script = new Probe();
        for (int seed = 0; seed < 20; seed++) {
            Seeded q = new Seeded(0xC40C1L + seed);
            ClockPort port = ports.apply(m, ClockWorld.vanilla());
            long dayTime = 0;
            for (int step = 0; step < 400; step++) {
                boolean daylight = q.nextInt(5) != 0;
                if (q.nextInt(30) == 0 && dayTime % 24000 != 0) {
                    port.sleep("minecraft:overworld", daylight);
                    dayTime = PreTwentySix.sleepSkip(dayTime, daylight);
                } else if (q.nextInt(40) == 0) {
                    long value = q.nextInt(1_000_000);
                    port.apply(OVERWORLD, new ClockCommand.SetTicks(value));
                    dayTime = value;
                } else {
                    port.tick(daylight);
                    dayTime = PreTwentySix.tickTime(dayTime, daylight);
                }
                final long want = dayTime;
                final int at = step;
                script.expect(port.state(OVERWORLD).totalTicks() == want,
                        () -> "step " + at + ": the clock reads " + port.state(OVERWORLD).totalTicks() + ", the old game " + want);
            }
        }
        script.report(c, "C4:script-matches-old-game");

        ClockPort port = ports.apply(m, ClockWorld.vanilla());
        port.apply(OVERWORLD, new ClockCommand.SetTicks(14000L));
        port.apply(StandardTimelines.THE_END, new ClockCommand.SetTicks(5555L));
        Optional<Applied> overworld = port.sleep("minecraft:overworld", true);
        c.that(overworld.isPresent() && overworld.get().move().equals(Optional.of(MoveResult.MOVED)), "C4:sleep-uses-default-clock");
        c.equalLong(24000L, port.state(OVERWORLD).totalTicks(), "C4:sleep-uses-default-clock");
        c.equalLong(5555L, port.state(StandardTimelines.THE_END).totalTicks(), "C4:sleep-moves-default-clock-only");

        Optional<Applied> end = port.sleep("minecraft:the_end", true);
        c.that(end.isPresent() && end.get().move().equals(Optional.of(MoveResult.NO_SUCH_MARKER)), "C4:no-wake-marker-no-skip");
        c.equalLong(5555L, port.state(StandardTimelines.THE_END).totalTicks(), "C4:no-wake-marker-no-skip");
        c.equalLong(24000L, port.state(OVERWORLD).totalTicks(), "C4:sleep-moves-default-clock-only");

        Optional<Applied> nether = port.sleep("minecraft:the_nether", true);
        c.that(!nether.isPresent(), "C4:no-default-clock-no-skip");
        c.equalLong(24000L, port.state(OVERWORLD).totalTicks(), "C4:no-default-clock-no-skip");
        c.equalLong(5555L, port.state(StandardTimelines.THE_END).totalTicks(), "C4:no-default-clock-no-skip");
        c.that(!ClockWorld.vanilla().defaultClock("minecraft:the_nether").isPresent(), "C4:no-default-clock-no-skip");
        c.equal(Optional.of(OVERWORLD), ClockWorld.vanilla().defaultClock("minecraft:overworld"), "C4:default-clock-listed");
        c.equal(Optional.of(OVERWORLD), port.world().defaultClock("minecraft:overworld"), "C4:default-clock-listed");
        c.equal(Optional.of(StandardTimelines.THE_END), port.world().defaultClock("minecraft:the_end"), "C4:default-clock-listed");
        c.that(!port.world().defaultClock("minecraft:the_nether").isPresent(), "C4:default-clock-listed");

        // A mod's clock is a clock like any other: a calendar with a long period, a marker, a sleep marker of its own.
        ClockWorld calendar = ClockWorld.vanilla()
                .with(Timeline.on(CALENDAR, "mod:great_year", 960_000)
                        .marker("mod:turning", 240_000)
                        .marker(StandardTimelines.WAKE_UP_FROM_SLEEP, 500)
                        .build())
                .withDefault("mod:dream", CALENDAR);
        ClockPort modPort = ports.apply(m, calendar);
        modPort.apply(CALENDAR, new ClockCommand.SetTicks(700L));
        c.equal(Optional.of(MoveResult.MOVED), modPort.apply(CALENDAR, new ClockCommand.MoveToMarker("mod:turning")).move(), "C4:a-mods-clock");
        c.equalLong(240_000L, modPort.state(CALENDAR).totalTicks(), "C4:a-mods-clock");
        modPort.apply(OVERWORLD, new ClockCommand.SetTicks(14000L));
        modPort.sleep("minecraft:overworld", true);
        c.equalLong(240_000L, modPort.state(CALENDAR).totalTicks(), "C4:sleep-moves-default-clock-only");
        modPort.sleep("mod:dream", true);
        c.equalLong(960_500L, modPort.state(CALENDAR).totalTicks(), "C4:a-mods-clock");
        c.equal(Optional.of(CALENDAR), modPort.world().defaultClock("mod:dream"), "C4:default-clock-listed");
        c.equalLong(1L, m.periodCount(calendar.timelinesOn(CALENDAR).get(0), modPort.state(CALENDAR).totalTicks()), "C4:a-mods-clock");
    }

    // ------------------------------------------------------------------------------------------------ C5

    /** C5: a client extrapolating from a synced state at the same game time reads the server's ticks. */
    private void c5Sync(Check c) {
        // A clock that is still is sent still.
        ClockState running = new ClockState(10L, 0.25F, 2.0F, false);
        c.equal(new NetworkState(10L, 0.25F, 2.0F), m.network(running, true), "C5:still-is-sent-still");
        c.equal(new NetworkState(10L, 0.25F, 0.0F), m.network(running.withPaused(true), true), "C5:still-is-sent-still");
        c.equal(new NetworkState(10L, 0.25F, 0.0F), m.network(running, false), "C5:still-is-sent-still");
        NetworkState flat = m.network(running.withPaused(true), true);
        c.equalLong(10L, m.extrapolate(flat, 5000L).totalTicks(), "C5:still-is-sent-still");

        // Exact where float arithmetic is exact: the client reads the server to the tick and the fraction.
        Seeded r = new Seeded(0xC50C0L);
        float[] dyadic = {0.125F, 0.25F, 0.5F, 1.0F, 1.5F, 2.0F, 3.0F, 4.25F};
        Probe exact = new Probe();
        for (int i = 0; i < 100; i++) {
            float rate = dyadic[r.nextInt(dyadic.length)];
            ClockState s0 = new ClockState(r.nextInt(1_000_000), 0.0F, rate, false);
            int n = r.nextInt(24001);
            ClockState server = run(s0, n, true);
            NetworkState sent = m.network(s0, true);
            NetworkState bulk = m.extrapolate(sent, n);
            NetworkState stepped = sent;
            for (int k = 0; k < n; k++) {
                stepped = m.extrapolate(stepped, 1L);
            }
            final NetworkState b = bulk;
            final NetworkState st = stepped;
            exact.expect(bulk.totalTicks() == server.totalTicks() && bulk.partialTick() == server.partialTick()
                            && stepped.totalTicks() == server.totalTicks() && stepped.partialTick() == server.partialTick(),
                    () -> "rate " + rate + " for " + n + " ticks: server " + server + ", client bulk " + b + ", stepped " + st);
        }
        exact.report(c, "C5:sync-agrees-exactly");

        // Otherwise within one tick: the server adds floats tick by tick, the client multiplies in double.
        Probe within = new Probe();
        for (int i = 0; i < 200; i++) {
            float rate = (float) (r.nextDouble() * 8.0);
            ClockState s0 = new ClockState(r.nextInt(1_000_000), (float) (r.nextDouble() * 0.99), rate, false);
            int n = r.nextInt(24001);
            ClockState server = run(s0, n, true);
            NetworkState sent = m.network(s0, true);
            NetworkState bulk = m.extrapolate(sent, n);
            NetworkState stepped = sent;
            for (int k = 0; k < n; k++) {
                stepped = m.extrapolate(stepped, 1L);
            }
            final NetworkState b = bulk;
            final NetworkState st = stepped;
            within.expect(Math.abs(bulk.totalTicks() - server.totalTicks()) <= 1 && Math.abs(stepped.totalTicks() - server.totalTicks()) <= 1,
                    () -> "rate " + rate + " for " + n + " ticks: server " + server + ", client bulk " + b + ", stepped " + st);
        }
        within.report(c, "C5:sync-within-one-tick");

        // Joins and changes: a client told of every change, and of everything on join, follows every clock.
        Probe follows = new Probe();
        for (int seed = 0; seed < 20; seed++) {
            Seeded q = new Seeded(0xC50C1L + seed);
            ClockWorld world = ClockWorld.vanilla().with(Timeline.on(CALENDAR, "mod:great_year", 960_000)
                    .marker("mod:turning", 240_000).marker("mod:dusk", 700_000).build());
            ClockPort port = ports.apply(m, world);
            List<ClockId> clocks = new ArrayList<>(world.clocks());
            ClientClocks client = null;
            boolean rule = true;
            for (int step = 0; step < 500; step++) {
                switch (q.nextInt(12)) {
                    case 0 -> {
                        ClockId id = clocks.get(q.nextInt(clocks.size()));
                        ClockCommand cmd;
                        switch (q.nextInt(6)) {
                            case 0 -> cmd = new ClockCommand.SetTicks(q.nextInt(2_000_000));
                            case 1 -> cmd = new ClockCommand.AddTicks(q.nextInt(10000) - 2000);
                            case 2 -> cmd = new ClockCommand.Pause();
                            case 3 -> cmd = new ClockCommand.Resume();
                            case 4 -> cmd = new ClockCommand.SetRate((float) (q.nextDouble() * 4.0));
                            default -> cmd = new ClockCommand.MoveToMarker(id.equals(CALENDAR) ? "mod:turning" : "minecraft:noon");
                        }
                        port.apply(id, cmd);
                        if (client != null) {
                            client.handle(port.sync(id, rule));
                        }
                    }
                    case 1 -> {
                        rule = !rule;
                        if (client != null) {
                            client.handle(port.sync(rule));
                        }
                    }
                    case 2 -> {
                        if (client == null) {
                            client = new ClientClocks(m);
                            client.tick(port.gameTime());
                            client.handle(port.sync(rule));
                        }
                    }
                    case 3 -> {
                        port.sleep("minecraft:overworld", rule);
                        if (client != null) {
                            client.handle(port.sync(OVERWORLD, rule));
                        }
                    }
                    default -> {
                        port.tick(rule);
                        if (client != null) {
                            client.tick(port.gameTime());
                        }
                    }
                }
            }
            if (client != null) {
                for (ClockId id : clocks) {
                    final ClientClocks cl = client;
                    final int at = seed;
                    follows.expect(Math.abs(cl.read(id).totalTicks() - port.state(id).totalTicks()) <= 1,
                            () -> "seed " + at + " clock " + id + ": client " + cl.read(id) + ", server " + port.state(id));
                }
            }
        }
        follows.report(c, "C5:join-and-change-agree");
    }

    // ------------------------------------------------------------------------------------------------ C6

    /** C6: the same states and commands give the same results, with no wall clock and nothing remembered between calls. */
    private void c6Determinism(Check c) {
        List<String> first = script(0xC60C0L);
        List<String> second = script(0xC60C0L);
        c.that(first.equals(second), "C6:deterministic");
        List<String> otherSeed = script(0xC60C1L);
        List<String> again = script(0xC60C0L);
        c.that(first.equals(again) && !otherSeed.equals(first), "C6:deterministic");

        List<String> portFirst = portScript(0xC60C2L);
        List<String> portSecond = portScript(0xC60C2L);
        c.that(portFirst.equals(portSecond), "C6:deterministic");
    }

    private List<String> script(long seed) {
        Seeded r = new Seeded(seed);
        List<String> log = new ArrayList<>();
        Timeline day = StandardTimelines.day();
        List<Marker> markers = day.markers();
        ClockState s = ClockState.start();
        for (int i = 0; i < 3000; i++) {
            switch (r.nextInt(10)) {
                case 0 -> s = m.set(s, r.nextInt(500_000));
                case 1 -> s = m.rate(s, (float) (r.nextDouble() * 3));
                case 2 -> s = m.pause(s, r.nextBoolean());
                case 3 -> {
                    Move mv = m.moveTo(s, Optional.of(markers.get(r.nextInt(markers.size()))));
                    s = mv.state();
                    log.add(mv.result().name());
                }
                case 4 -> {
                    Move mv = m.sleep(s, Optional.of(markers.get(markers.size() - 1)), r.nextBoolean());
                    s = mv.state();
                    log.add(mv.result().name());
                }
                default -> s = m.tick(s, r.nextInt(8) != 0);
            }
            log.add(s.toString());
            log.add(m.network(s, true).toString());
            log.add(Long.toString(m.periodCount(day, s.totalTicks())) + m.edgesBetween(markers.get(0), s.totalTicks(), s.totalTicks() + 30000));
        }
        return log;
    }

    private List<String> portScript(long seed) {
        Seeded r = new Seeded(seed);
        ClockPort port = ports.apply(m, ClockWorld.vanilla());
        List<String> log = new ArrayList<>();
        Set<ClockId> clocks = new LinkedHashSet<>(port.world().clocks());
        for (int i = 0; i < 1500; i++) {
            switch (r.nextInt(6)) {
                case 0 -> log.add(String.valueOf(port.apply(OVERWORLD, new ClockCommand.SetTicks(r.nextInt(100_000)))));
                case 1 -> log.add(String.valueOf(port.sleep("minecraft:overworld", r.nextBoolean())));
                case 2 -> log.add(String.valueOf(port.apply(OVERWORLD, new ClockCommand.MoveToMarker("minecraft:midnight"))));
                default -> port.tick(r.nextInt(6) != 0);
            }
            for (ClockId id : clocks) {
                log.add(port.state(id).toString());
            }
            log.add(port.sync(true).toString());
        }
        return Collections.unmodifiableList(log);
    }
}
