package mycelium.flow.v1.harness;

import mycelium.flow.v1.fixtures.OneWaySourceBackend;
import mycelium.flow.v1.fixtures.OverpromisingBackend;
import mycelium.flow.v1.fixtures.StuckSourceBackend;
import mycelium.flow.v1.fixtures.UnqualifiedBackend;
import mycelium.harness.Check;
import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.Reason;
import mycelium.flow.v1.model.ResourceKey;
import mycelium.flow.v1.model.TransferExecutor;
import mycelium.flow.v1.model.TransferOutcome;

import java.util.function.LongFunction;

public final class FlowContractTests {
    private static final ResourceKey IRON = new ResourceKey("iron_ingot");
    private static final ResourceKey GOLD = new ResourceKey("gold_ingot");

    private final TransferExecutor executor;
    private final LongFunction<FlowBackend> sourceFactory;
    private final LongFunction<FlowBackend> destinationFactory;

    public FlowContractTests(TransferExecutor executor, LongFunction<FlowBackend> sourceFactory,
                             LongFunction<FlowBackend> destinationFactory) {
        this.executor = executor;
        this.sourceFactory = sourceFactory;
        this.destinationFactory = destinationFactory;
    }

    public Check run() {
        Check check = new Check();
        t1PositiveFixture(check);
        t2ZeroRequest(check);
        t3EmptySource(check);
        t4FullDestination(check);
        t5PartialCapacity(check);
        t6FinalityOnAbort(check);
        t7FinalityOnCommit(check);
        t8IdentityPreservation(check);
        t9UnsupportedTrait(check);
        t10AntiEvasion(check);
        t11AdmitsLessThanPromised(check);
        t12UnrestorableRemainder(check);
        t13SourceRefuses(check);
        return check;
    }

    private void t1PositiveFixture(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, feedback);
        c.that(outcome instanceof TransferOutcome.Committed, "T1:outcome-committed");
        if (outcome instanceof TransferOutcome.Committed committed) {
            c.that(committed.receipt().committed() == 7, "T1:committed-7");
            c.that(committed.receipt().requested() == 12, "T1:requested-12");
        }
        tx.commit();
        c.that(source.available(IRON) == 13, "T1:source-13");
        c.that(destination.available(IRON) == 7, "T1:destination-7");
        c.that(feedback.events().size() == 1, "T1:feedback-once");
        if (feedback.events().size() == 1) {
            c.that(feedback.events().get(0).committed() == 7, "T1:feedback-quantity");
        }
    }

    private void t2ZeroRequest(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 0, tx, feedback);
        c.that(outcome instanceof TransferOutcome.Rejected rejected && rejected.reason() == Reason.ZERO_REQUEST,
                "T2:zero-request-rejected");
        tx.commit();
        c.that(source.available(IRON) == 20, "T2:source-unchanged");
        c.that(destination.available(IRON) == 0, "T2:destination-unchanged");
        c.that(feedback.events().isEmpty(), "T2:no-feedback");
    }

    private void t3EmptySource(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        FlowBackend destination = destinationFactory.apply(7);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(outcome instanceof TransferOutcome.Rejected rejected && rejected.reason() == Reason.EMPTY_SOURCE,
                "T3:empty-source-rejected");
        tx.commit();
        c.that(source.available(IRON) == 0, "T3:source-unchanged");
        c.that(destination.available(IRON) == 0, "T3:destination-unchanged");
    }

    private void t4FullDestination(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        destination.seed(IRON, 7);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(outcome instanceof TransferOutcome.Rejected rejected && rejected.reason() == Reason.FULL_DESTINATION,
                "T4:full-destination-rejected");
        tx.commit();
        c.that(source.available(IRON) == 20, "T4:source-unchanged");
        c.that(destination.available(IRON) == 7, "T4:destination-unchanged");
    }

    private void t5PartialCapacity(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(3);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, feedback);
        c.that(outcome instanceof TransferOutcome.Committed committed && committed.receipt().committed() == 3,
                "T5:committed-3");
        tx.commit();
        c.that(source.available(IRON) == 17, "T5:source-17");
        c.that(destination.available(IRON) == 3, "T5:destination-3");
    }

    private void t6FinalityOnAbort(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction outer = FlowTransaction.open();
        FlowTransaction nested = outer.openNested();
        executor.execute(source, destination, IRON, 12, nested, feedback);
        nested.commit();
        outer.abort();
        c.that(source.available(IRON) == 20, "T6:state-restored-source");
        c.that(destination.available(IRON) == 0, "T6:state-restored-destination");
        c.that(feedback.events().isEmpty(), "T6:no-feedback-after-abort");
    }

    private void t7FinalityOnCommit(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction outer = FlowTransaction.open();
        FlowTransaction nested = outer.openNested();
        executor.execute(source, destination, IRON, 12, nested, feedback);
        nested.commit();
        c.that(feedback.events().isEmpty(), "T7:no-feedback-before-outer-commit");
        outer.commit();
        c.that(source.available(IRON) == 13, "T7:source-13");
        c.that(destination.available(IRON) == 7, "T7:destination-7");
        c.that(feedback.events().size() == 1, "T7:feedback-once");
    }

    private void t8IdentityPreservation(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        source.seed(GOLD, 5);
        FlowBackend destination = destinationFactory.apply(64);
        destination.seed(GOLD, 2);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(outcome instanceof TransferOutcome.Committed committed && committed.receipt().committed() == 12,
                "T8:committed-12");
        tx.commit();
        c.that(destination.available(IRON) == 12, "T8:transferred-quantity");
        c.that(destination.available(GOLD) == 2, "T8:other-resource-destination");
        c.that(source.available(GOLD) == 5, "T8:other-resource-source");
        c.that(source.available(IRON) == 8, "T8:source-iron");
    }

    private void t9UnsupportedTrait(Check c) {
        FlowBackend source = new UnqualifiedBackend(64);
        source.seed(IRON, 20);
        FlowBackend destination = new UnqualifiedBackend(7);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(outcome instanceof TransferOutcome.Unsupported, "T9:unsupported-outcome");
        tx.commit();
        c.that(source.available(IRON) == 20, "T9:no-mutation");
    }

    private void t10AntiEvasion(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(!(outcome instanceof TransferOutcome.Unsupported), "T10:no-evasion-unsupported");
        c.that(!(outcome instanceof TransferOutcome.Rejected), "T10:no-evasion-rejected");
        c.that(outcome instanceof TransferOutcome.Committed, "T10:committed-expected");
        tx.commit();
    }

    /**
     * SPEC §6: a destination admits less than it promised, so the executor restores the remainder to the source, and the
     * receipt says what the destination accepted. Admitting nothing at all is a rejection with everything restored.
     */
    private void t11AdmitsLessThanPromised(Check c) {
        FlowBackend source = sourceFactory.apply(64);
        source.seed(IRON, 20);
        FlowBackend destination = new OverpromisingBackend(4, 7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, feedback);
        c.that(outcome instanceof TransferOutcome.Committed committed && committed.receipt().committed() == 4,
                "T11:committed-accepted");
        tx.commit();
        c.that(source.available(IRON) == 16, "T11:source-restored");
        c.that(destination.available(IRON) == 4, "T11:destination-accepted");
        c.that(feedback.events().size() == 1 && feedback.events().get(0).committed() == 4, "T11:feedback-accepted");

        FlowBackend source2 = sourceFactory.apply(64);
        source2.seed(IRON, 20);
        FlowBackend nothing = new OverpromisingBackend(0, 7);
        FeedbackSink feedback2 = new FeedbackSink();
        FlowTransaction tx2 = FlowTransaction.open();
        TransferOutcome outcome2 = executor.execute(source2, nothing, IRON, 12, tx2, feedback2);
        c.that(outcome2 instanceof TransferOutcome.Rejected rejected && rejected.reason() == Reason.TRANSFER_FAILED,
                "T11:nothing-admitted-rejected");
        tx2.commit();
        c.that(source2.available(IRON) == 20 && nothing.available(IRON) == 0, "T11:nothing-admitted-restored");
        c.that(feedback2.events().isEmpty(), "T11:nothing-admitted-no-feedback");
    }

    /**
     * SPEC §6: the remainder cannot go back (the source will not take it). The outcome is unconfirmed, and an unconfirmed
     * outcome is never an ordinary Rejected (DESIGN §7): the executor throws, and the caller's abort leaves no change.
     */
    private void t12UnrestorableRemainder(Check c) {
        FlowBackend source = new OneWaySourceBackend(64);
        source.seed(IRON, 20);
        FlowBackend destination = new OverpromisingBackend(4, 7);
        FeedbackSink feedback = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        c.throwsAny(() -> executor.execute(source, destination, IRON, 12, tx, feedback), "T12:unrestorable-remainder-throws");
        tx.abort();
        c.that(source.available(IRON) == 20 && destination.available(IRON) == 0, "T12:abort-leaves-no-change");
        c.that(feedback.events().isEmpty(), "T12:no-feedback");
    }

    /** SPEC §6: a source that reports stock but gives none is the source's refusal: EXTRACTION_FAILED, nothing moved. */
    private void t13SourceRefuses(Check c) {
        FlowBackend source = new StuckSourceBackend(64);
        source.seed(IRON, 20);
        FlowBackend destination = destinationFactory.apply(7);
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome outcome = executor.execute(source, destination, IRON, 12, tx, new FeedbackSink());
        c.that(outcome instanceof TransferOutcome.Rejected rejected && rejected.reason() == Reason.EXTRACTION_FAILED,
                "T13:extraction-failed");
        tx.commit();
        c.that(source.available(IRON) == 20 && destination.available(IRON) == 0, "T13:no-change");
    }
}
