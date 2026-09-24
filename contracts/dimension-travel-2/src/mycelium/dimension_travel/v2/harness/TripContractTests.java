package mycelium.dimension_travel.v2.harness;

import mycelium.dimension_travel.v2.fixtures.MemoryTravelPort;
import mycelium.dimension_travel.v2.fixtures.UnjournaledPort;
import mycelium.dimension_travel.v2.model.DimensionId;
import mycelium.dimension_travel.v2.model.Inventory;
import mycelium.dimension_travel.v2.model.ItemToken;
import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.Pose;
import mycelium.dimension_travel.v2.model.Position;
import mycelium.dimension_travel.v2.model.Reason;
import mycelium.dimension_travel.v2.model.RecoverOutcome;
import mycelium.dimension_travel.v2.model.TravelOutcome;
import mycelium.dimension_travel.v2.model.TravelTx;
import mycelium.dimension_travel.v2.model.TripExecutor;
import mycelium.harness.Check;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Fixtures T1..T11 for {@code dimension.travel@2}: a trip is journaled before it moves anyone. */
public final class TripContractTests {
    private static final PlayerId PLAYER = new PlayerId("dreamer");
    private static final DimensionId OVERWORLD = new DimensionId("minecraft:overworld");
    private static final DimensionId DREAM = new DimensionId("example:dream");
    private static final Position HOME = new Position(OVERWORLD, 10, 64, 20, 90f, 0f);
    private static final Position AWAY = new Position(DREAM, 10, 64, 20, 90f, 0f);
    private static final Inventory CARRIED = new Inventory(List.of(new ItemToken("torch", 3), new ItemToken("bread", 2)));
    private static final Inventory FOUND = new Inventory(List.of(new ItemToken("torch", 3), new ItemToken("diamond", 9)));

    private final TripExecutor trips;
    private final Supplier<MemoryTravelPort> ports;

    public TripContractTests(TripExecutor trips, Supplier<MemoryTravelPort> ports) {
        this.trips = trips;
        this.ports = ports;
    }

    /** What a consumer's ledger was asked to settle. */
    static final class Ledger {
        final List<Journal> settled = new ArrayList<>();
        final List<Inventory> seen = new ArrayList<>();

        void settle(Journal j, Inventory now) {
            settled.add(j);
            seen.add(now);
        }
    }

    public Check run() {
        Check c = new Check();
        t1JournalBeforeMove(c);
        t2OneOpenTrip(c);
        t3JournalSurvivesCrash(c);
        t4RejectionLeavesNoJournal(c);
        t5ReturnStrikes(c);
        t6RecoverSettlesOnce(c);
        t7NoSettleBeforeCommit(c);
        t8RecoverIdempotent(c);
        t9AlreadyHome(c);
        t10Unsupported(c);
        t11AntiEvasion(c);
        return c;
    }

    private MemoryTravelPort fresh() {
        MemoryTravelPort p = ports.get();
        p.seed(PLAYER, HOME, CARRIED);
        return p;
    }

    private TravelOutcome depart(MemoryTravelPort port) {
        TravelTx tx = TravelTx.open();
        TravelOutcome o = trips.depart(port, PLAYER, AWAY, Pose.GETTING_UP, tx, r -> { });
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
        return o;
    }

    /** L1, L8, P1: the journal is written before the move, and records home and what was carried, exactly. */
    private void t1JournalBeforeMove(Check c) {
        MemoryTravelPort port = fresh();
        TravelOutcome o = depart(port);
        c.that(o instanceof TravelOutcome.Committed, "T1:departed");
        c.that(port.journal(PLAYER).isPresent(), "T1:journal-written");
        c.that(Boolean.TRUE.equals(port.journalSeenAtMove), "T1:journal-before-move");
        port.journal(PLAYER).ifPresent(j -> {
            c.equal(HOME, j.origin(), "T1:journal-origin");
            c.equal(CARRIED, j.carried(), "T1:journal-carried");
        });
        c.equal(AWAY, port.where(PLAYER), "T1:arrived");
    }

    /** L2: one open trip; a second departure is refused and nothing moves or is rewritten. */
    private void t2OneOpenTrip(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        Journal first = port.journal(PLAYER).orElse(null);
        TravelTx tx = TravelTx.open();
        TravelOutcome again = trips.depart(port, PLAYER, new Position(DREAM, 99, 64, 99, 0f, 0f), Pose.STANDING, tx, r -> { });
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
        c.that(again instanceof TravelOutcome.Rejected r && r.reason() == Reason.TRIP_OPEN, "T2:second-departure-rejected");
        c.equal(first, port.journal(PLAYER).orElse(null), "T2:home-not-overwritten");
    }

    /** L3: a crash mid-move leaves the journal in the durable store: the next process sees the trip. */
    private void t3JournalSurvivesCrash(Check c) {
        MemoryTravelPort port = fresh();
        port.failNextMove = true;
        TravelTx tx = TravelTx.open();
        TravelOutcome o = trips.depart(port, PLAYER, AWAY, Pose.GETTING_UP, tx, r -> { });
        c.that(o instanceof TravelOutcome.Indeterminate, "T3:indeterminate");
        MemoryTravelPort after = port.restart(); // the process died: no commit, no abort, no memory
        c.that(after.journal(PLAYER).isPresent(), "T3:journal-survives-crash");
    }

    /** L4: a rejected arrival leaves nothing: no journal, no move. */
    private void t4RejectionLeavesNoJournal(Check c) {
        MemoryTravelPort port = fresh();
        port.ignoreNextMove = true;
        TravelOutcome o = depart(port);
        c.that(o instanceof TravelOutcome.Rejected r && r.reason() == Reason.TARGET_UNLOADED, "T4:rejected");
        c.that(port.journal(PLAYER).isEmpty(), "T4:no-journal-after-rejection");
        c.equal(HOME, port.where(PLAYER), "T4:still-home");
    }

    /** L5: coming home strikes the trip. */
    private void t5ReturnStrikes(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        TravelTx tx = TravelTx.open();
        TravelOutcome o = trips.returnHome(port, PLAYER, Pose.SLEEPING, tx, r -> { });
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
        c.that(o instanceof TravelOutcome.Committed, "T5:returned");
        c.equal(HOME, port.where(PLAYER), "T5:home");
        c.that(port.journal(PLAYER).isEmpty(), "T5:journal-struck-on-return");
        MemoryTravelPort after = port.restart();
        TravelTx rtx = TravelTx.open();
        c.that(trips.recover(after, PLAYER, rtx, (j, n) -> { }) instanceof RecoverOutcome.Rejected, "T5:nothing-to-recover");
    }

    /** L6: after a crash away, recovery brings the traveller home and settles exactly once, with before and after. */
    private void t6RecoverSettlesOnce(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        port.give(PLAYER, FOUND);
        MemoryTravelPort after = port.restart();
        Ledger ledger = new Ledger();
        TravelTx tx = TravelTx.open();
        RecoverOutcome o = trips.recover(after, PLAYER, tx, ledger::settle);
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
        c.that(o instanceof RecoverOutcome.Recovered r && r.receipt().broughtHome(), "T6:recovered-home");
        c.equal(HOME, after.where(PLAYER), "T6:home");
        c.equalLong(1, ledger.settled.size(), "T6:settled-once");
        if (!ledger.settled.isEmpty()) {
            c.equal(CARRIED, ledger.settled.get(0).carried(), "T6:settled-with-what-was-carried");
            c.equal(FOUND, ledger.seen.get(0), "T6:settled-with-what-came-back");
        }
        c.that(after.journal(PLAYER).isEmpty(), "T6:journal-struck");
    }

    /** L7: settling is feedback: nothing before the outer commit, nothing at all if it aborts (and the trip stays open). */
    private void t7NoSettleBeforeCommit(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        MemoryTravelPort after = port.restart();
        Ledger ledger = new Ledger();
        TravelTx tx = TravelTx.open();
        trips.recover(after, PLAYER, tx, ledger::settle);
        c.that(ledger.settled.isEmpty(), "T7:no-settle-before-commit");
        tx.abort();
        c.that(ledger.settled.isEmpty(), "T7:no-settle-after-abort");
        c.that(after.journal(PLAYER).isPresent(), "T7:trip-still-open-after-abort");
    }

    /** L6: once settled, it is settled: a second recovery finds nothing and asks nothing. */
    private void t8RecoverIdempotent(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        MemoryTravelPort after = port.restart();
        Ledger ledger = new Ledger();
        for (int i = 0; i < 2; i++) {
            TravelTx tx = TravelTx.open();
            RecoverOutcome o = trips.recover(after, PLAYER, tx, ledger::settle);
            if (tx.state() == TravelTx.State.OPEN) {
                tx.commit();
            }
            if (i == 1) {
                c.that(o instanceof RecoverOutcome.Rejected r && r.reason() == Reason.NO_TRIP, "T8:second-recover-rejected");
            }
        }
        c.equalLong(1, ledger.settled.size(), "T8:settled-once-across-recoveries");
    }

    /** Already home (the process died after the move home but before the strike): strike, settle nothing. */
    private void t9AlreadyHome(Check c) {
        MemoryTravelPort port = fresh();
        depart(port);
        port.seed(PLAYER, HOME, CARRIED); // it got home; the strike never happened
        MemoryTravelPort after = port.restart();
        Ledger ledger = new Ledger();
        TravelTx tx = TravelTx.open();
        RecoverOutcome o = trips.recover(after, PLAYER, tx, ledger::settle);
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
        c.that(o instanceof RecoverOutcome.Recovered r && !r.receipt().broughtHome(), "T9:recovered-in-place");
        c.that(ledger.settled.isEmpty(), "T9:nothing-to-settle");
        c.that(after.journal(PLAYER).isEmpty(), "T9:journal-struck");
    }

    /** A port without JOURNALED: every verb is Unsupported and nothing changes. */
    private void t10Unsupported(Check c) {
        MemoryTravelPort port = new UnjournaledPort();
        port.seed(PLAYER, HOME, CARRIED);
        TravelTx tx = TravelTx.open();
        c.that(trips.depart(port, PLAYER, AWAY, Pose.GETTING_UP, tx, r -> { }) instanceof TravelOutcome.Unsupported, "T10:depart-unsupported");
        c.that(trips.recover(port, PLAYER, tx, (j, n) -> { }) instanceof RecoverOutcome.Unsupported, "T10:recover-unsupported");
        tx.commit();
        c.equal(HOME, port.where(PLAYER), "T10:no-move");
    }

    /** Anti-evasion: an admissible departure from a qualified port commits; it may not hide behind a refusal. */
    private void t11AntiEvasion(Check c) {
        TravelOutcome o = depart(fresh());
        c.that(!(o instanceof TravelOutcome.Unsupported), "T11:no-evasion-unsupported");
        c.that(!(o instanceof TravelOutcome.Rejected), "T11:no-evasion-rejected");
    }
}
