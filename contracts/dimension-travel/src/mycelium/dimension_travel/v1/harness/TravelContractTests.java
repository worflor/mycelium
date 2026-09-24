package mycelium.dimension_travel.v1.harness;

import mycelium.dimension_travel.v1.fixtures.HasMemory;
import mycelium.dimension_travel.v1.fixtures.MemoryTravelPort;
import mycelium.dimension_travel.v1.fixtures.LaggyPort;
import mycelium.dimension_travel.v1.fixtures.UnqualifiedTravelPort;
import mycelium.dimension_travel.v1.fixtures.UnstablePort;
import mycelium.dimension_travel.v1.model.DimensionId;
import mycelium.dimension_travel.v1.model.Inventory;
import mycelium.dimension_travel.v1.model.ItemToken;
import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.Reason;
import mycelium.dimension_travel.v1.model.Travel;
import mycelium.dimension_travel.v1.model.TravelExecutor;
import mycelium.dimension_travel.v1.model.TravelOutcome;
import mycelium.dimension_travel.v1.model.TravelPort;
import mycelium.dimension_travel.v1.model.TravelTx;
import mycelium.harness.Check;

import java.util.List;
import java.util.function.Supplier;

public final class TravelContractTests {
    private static final PlayerId PLAYER = new PlayerId("herobrine");
    private static final DimensionId OVERWORLD = new DimensionId("minecraft:overworld");
    private static final DimensionId DREAM = new DimensionId("example:dream");
    private static final Position SOURCE = new Position(OVERWORLD, 10, 64, 20, 90f, 0f);
    private static final Position TARGET = new Position(DREAM, 100, 80, -40, 0f, 0f);
    private static final Inventory SAMPLE_INVENTORY =
            new Inventory(List.of(new ItemToken("dream_dagger", 1), new ItemToken("torch", 3)));

    private final TravelExecutor executor;
    private final Supplier<TravelPort> portFactory;

    public TravelContractTests(TravelExecutor executor, Supplier<TravelPort> portFactory) {
        this.executor = executor;
        this.portFactory = portFactory;
    }

    public Check run() {
        Check check = new Check();
        t1PositiveTravel(check);
        t2InventoryPreserved(check);
        t3PositionExact(check);
        t4SamePositionRejected(check);
        t5NotFoundRejected(check);
        t6DriftSameCoordinate(check);
        t7FinalityOnAbort(check);
        t8FinalityOnCommit(check);
        t9IndeterminateOnFailure(check);
        t10TargetUnloadedBeforeTick(check);
        t11UnsupportedTrait(check);
        t12AntiEvasion(check);
        return check;
    }

    private static void commitIfOpen(TravelTx tx) {
        if (tx.state() == TravelTx.State.OPEN) {
            tx.commit();
        }
    }

    private void t1PositiveTravel(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Committed, "T1:outcome-committed");
        if (outcome instanceof TravelOutcome.Committed committed) {
            c.that(committed.receipt().to().equals(TARGET), "T1:receipt-target");
        }
        commitIfOpen(tx);
        c.that(feedback.events().size() == 1, "T1:feedback-once");
    }

    private void t2InventoryPreserved(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        Inventory before = port.inventory(PLAYER);
        TravelTx tx = TravelTx.open();
        executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, new FeedbackSink());
        commitIfOpen(tx);
        Inventory after = port.inventory(PLAYER);
        c.that(before.equals(after), "T2:inventory-unchanged");
    }

    private void t3PositionExact(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        TravelTx tx = TravelTx.open();
        executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, new FeedbackSink());
        commitIfOpen(tx);
        c.that(port.where(PLAYER).equals(TARGET), "T3:position-exact");
    }

    private void t4SamePositionRejected(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, SOURCE, Pose.STANDING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Rejected rejected && rejected.reason() == Reason.SAME_POSITION,
                "T4:same-position-rejected");
        commitIfOpen(tx);
        c.that(feedback.events().isEmpty(), "T4:no-feedback");
    }

    private void t5NotFoundRejected(Check c) {
        TravelPort port = portFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Rejected rejected && rejected.reason() == Reason.NOT_FOUND,
                "T5:not-found-rejected");
        commitIfOpen(tx);
        c.that(feedback.events().isEmpty(), "T5:not-found-no-feedback");
    }

    private void t6DriftSameCoordinate(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = Travel.drift(port, PLAYER, DREAM, Pose.SLEEPING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Committed, "T6:drift-committed");
        commitIfOpen(tx);
        Position after = port.where(PLAYER);
        c.that(after.x() == SOURCE.x() && after.y() == SOURCE.y() && after.z() == SOURCE.z(),
                "T6:coordinates-preserved");
        c.that(after.dimension().equals(DREAM), "T6:dimension-changed");
    }

    private void t7FinalityOnAbort(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        FeedbackSink feedback = new FeedbackSink();
        TravelTx outer = TravelTx.open();
        TravelTx nested = outer.openNested();
        executor.execute(port, PLAYER, TARGET, Pose.STANDING, nested, feedback);
        commitIfOpen(nested);
        outer.abort();
        c.that(port.where(PLAYER).equals(SOURCE), "T7:state-restored-after-abort");
        c.that(feedback.events().isEmpty(), "T7:no-feedback-after-abort");
    }

    private void t8FinalityOnCommit(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        FeedbackSink feedback = new FeedbackSink();
        TravelTx outer = TravelTx.open();
        TravelTx nested = outer.openNested();
        executor.execute(port, PLAYER, TARGET, Pose.STANDING, nested, feedback);
        commitIfOpen(nested);
        c.that(feedback.events().isEmpty(), "T8:no-feedback-before-outer-commit");
        outer.commit();
        c.that(port.where(PLAYER).equals(TARGET), "T8:committed-after-outer-commit");
        c.that(feedback.events().size() == 1, "T8:feedback-once");
    }

    private void t9IndeterminateOnFailure(Check c) {
        UnstablePort port = new UnstablePort();
        port.memory().seed(PLAYER, SOURCE, SAMPLE_INVENTORY);
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Indeterminate, "T9:indeterminate-on-failure");
        c.that(!(outcome instanceof TravelOutcome.Rejected), "T9:not-collapsed-to-rejected");
        commitIfOpen(tx);
        c.that(feedback.events().isEmpty(), "T9:no-feedback-on-indeterminate");
    }

    private void t10TargetUnloadedBeforeTick(Check c) {
        LaggyPort port = new LaggyPort().seed(PLAYER, SOURCE, SAMPLE_INVENTORY);
        FeedbackSink feedback = new FeedbackSink();
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, feedback);
        c.that(outcome instanceof TravelOutcome.Rejected rejected && rejected.reason() == Reason.TARGET_UNLOADED,
                "T10:target-unloaded-rejected-before-tick");
        commitIfOpen(tx);
        c.that(port.where(PLAYER).equals(SOURCE), "T10:position-unconfirmed-until-tick");
        c.that(feedback.events().isEmpty(), "T10:no-feedback-before-tick");
        port.tick();
        c.that(port.where(PLAYER).equals(TARGET), "T10:position-applied-after-tick");
    }

    private void t11UnsupportedTrait(Check c) {
        UnqualifiedTravelPort port = new UnqualifiedTravelPort();
        port.memory().seed(PLAYER, SOURCE, SAMPLE_INVENTORY);
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, new FeedbackSink());
        c.that(outcome instanceof TravelOutcome.Unsupported, "T11:unsupported-outcome");
        commitIfOpen(tx);
        c.that(port.where(PLAYER).equals(SOURCE), "T11:no-mutation");
    }

    private void t12AntiEvasion(Check c) {
        TravelPort port = seedFresh(portFactory.get());
        TravelTx tx = TravelTx.open();
        TravelOutcome outcome = executor.execute(port, PLAYER, TARGET, Pose.STANDING, tx, new FeedbackSink());
        c.that(!(outcome instanceof TravelOutcome.Unsupported), "T12:no-evasion-unsupported");
        c.that(!(outcome instanceof TravelOutcome.Rejected), "T12:no-evasion-rejected");
        c.that(outcome instanceof TravelOutcome.Committed, "T12:committed-expected");
        commitIfOpen(tx);
    }

    /**
     * Seeds the player into whichever backing {@link MemoryTravelPort} the given port uses. The reference
     * port seeds directly; every broken-port fixture in this package forwards to its own delegate and
     * exposes it via {@code memory()} (see {@code ForwardingTravelPort} / {@code LaggyPort}).
     */
    private static TravelPort seedFresh(TravelPort port) {
        if (port instanceof MemoryTravelPort memory) {
            memory.seed(PLAYER, SOURCE, SAMPLE_INVENTORY);
        } else if (port instanceof HasMemory hasMemory) {
            hasMemory.memory().seed(PLAYER, SOURCE, SAMPLE_INVENTORY);
        } else {
            throw new IllegalStateException("fixture must be a MemoryTravelPort or expose memory() for seeding");
        }
        return port;
    }
}
