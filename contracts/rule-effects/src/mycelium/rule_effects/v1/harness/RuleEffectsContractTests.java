package mycelium.rule_effects.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Suite;
import mycelium.rule_effects.v1.model.EffectExecutor;
import mycelium.rule_effects.v1.model.EffectLedger;
import mycelium.rule_effects.v1.model.EffectOutcome;
import mycelium.rule_effects.v1.model.EffectPort;
import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Leak;
import mycelium.rule_effects.v1.model.Op;
import mycelium.rule_effects.v1.model.Reason;
import mycelium.rule_effects.v1.model.Receipt;
import mycelium.rule_effects.v1.model.RuleEffects;
import mycelium.rule_effects.v1.model.Tx;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

public final class RuleEffectsContractTests {
    private static final String RULE = "example:low_gravity";
    private static final String OTHER_RULE = "example:heavy";
    private static final EntityId A = new EntityId("player-a");
    private static final EntityId B = new EntityId("player-b");
    private static final EntityId OUTSIDE = new EntityId("player-outside");
    private static final List<EffectSpec> EFFECTS = List.of(
            new EffectSpec.Attribute("minecraft:gravity", Op.MULTIPLY_TOTAL, -0.65, "example:low_gravity/gravity"),
            new EffectSpec.Attribute("minecraft:safe_fall_distance", Op.ADD, 12, "example:low_gravity/safe_fall"),
            new EffectSpec.Flag("example:low_gravity/floaty"));
    private static final Set<String> IDS = Set.of(
            "example:low_gravity/gravity", "example:low_gravity/safe_fall", "example:low_gravity/floaty");

    private final EffectExecutor executor;
    private final Supplier<? extends EffectPort> ports;

    public RuleEffectsContractTests(EffectExecutor executor, Supplier<? extends EffectPort> ports) {
        this.executor = executor;
        this.ports = ports;
    }

    public Check run() {
        return new Suite("rule.effects@1")
                .add("T1", this::t1ApplyPositive)
                .add("T2", this::t2EmptyScope)
                .add("T3", this::t3NoStacking)
                .add("T4", this::t4Symmetry)
                .add("T5", this::t5NothingToRemove)
                .add("T6", this::t6FinalityOnAbort)
                .add("T7", this::t7FinalityOnCommit)
                .add("T8", this::t8LedgerReconcile)
                .add("T9", this::t9AntiEvasion)
                .run();
    }

    private EffectPort freshPort() {
        EffectPort port = ports.get();
        port.know(A);
        port.know(B);
        port.know(OUTSIDE);
        return port;
    }

    private void t1ApplyPositive(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx tx = Tx.open();
        EffectOutcome outcome = executor.apply(port, Set.of(A, B), RULE, EFFECTS, tx, feedback);
        c.that(outcome instanceof EffectOutcome.Committed, "T1:outcome-committed");
        if (outcome instanceof EffectOutcome.Committed committed) {
            c.equal(IDS, committed.receipt().ids(), "T1:receipt-ids");
            c.equal(Set.of(A, B), committed.receipt().entities(), "T1:receipt-entities");
        }
        c.that(feedback.events().isEmpty(), "T1:no-feedback-before-commit");
        tx.commit();
        c.that(port.active(A).containsAll(IDS) && port.active(B).containsAll(IDS), "T1:active-contains-ids");
        c.equal(IDS, port.active(A), "T1:active-exact");
        c.equal(Set.of(), port.active(OUTSIDE), "T1:scope-untouched");
        c.equalLong(1, feedback.events().size(), "T1:feedback-once");
    }

    private void t2EmptyScope(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx tx = Tx.open();
        EffectOutcome outcome = executor.apply(port, Set.of(), RULE, EFFECTS, tx, feedback);
        c.that(outcome instanceof EffectOutcome.Rejected rejected && rejected.reason() == Reason.EMPTY_SCOPE,
                "T2:empty-scope-rejected");
        EffectOutcome none = executor.apply(port, Set.of(A), RULE, List.of(), tx, feedback);
        c.that(none instanceof EffectOutcome.Rejected rejected && rejected.reason() == Reason.EMPTY_EFFECTS,
                "T2:empty-effects-rejected");
        tx.commit();
        c.equal(Set.of(), port.active(A), "T2:no-mutation");
        c.that(feedback.events().isEmpty(), "T2:no-feedback");
    }

    private void t3NoStacking(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx first = Tx.open();
        executor.apply(port, Set.of(A), RULE, EFFECTS, first, feedback);
        first.commit();
        int afterFirst = port.active(A).size();
        Tx second = Tx.open();
        EffectOutcome outcome = executor.apply(port, Set.of(A), RULE, EFFECTS, second, feedback);
        c.that(outcome instanceof EffectOutcome.Committed, "T3:second-committed");
        if (outcome instanceof EffectOutcome.Committed committed) {
            c.equal(IDS, committed.receipt().ids(), "T3:receipt-ids-same");
        }
        second.commit();
        c.equalLong(afterFirst, port.active(A).size(), "T3:no-stacking");
        c.equal(IDS, port.active(A), "T3:active-exact");
    }

    private void t4Symmetry(Check c) {
        EffectPort port = freshPort();
        Set<String> before = port.active(A);
        FeedbackSink feedback = new FeedbackSink();
        EffectLedger ledger = EffectLedger.empty();
        Tx apply = Tx.open();
        EffectOutcome applied = executor.apply(port, Set.of(A, B), RULE, EFFECTS, apply, feedback);
        apply.commit();
        if (applied instanceof EffectOutcome.Committed committed
                && committed.receipt() instanceof Receipt.ApplyReceipt receipt) {
            ledger = ledger.withApplied(receipt);
        }
        Tx remove = Tx.open();
        EffectOutcome removed = RuleEffects.removeRule(port, Set.of(A, B), RULE, ledger, remove, feedback);
        c.that(removed instanceof EffectOutcome.Committed, "T4:remove-committed");
        if (removed instanceof EffectOutcome.Committed committed) {
            c.equal(IDS, committed.receipt().ids(), "T4:remove-ids");
        }
        remove.commit();
        c.equal(before, port.active(A), "T4:symmetry");
        c.equal(Set.of(), port.active(B), "T4:symmetry-b");
        c.equalLong(2, feedback.events().size(), "T4:feedback-apply-and-remove");
    }

    private void t5NothingToRemove(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx tx = Tx.open();
        EffectOutcome outcome = RuleEffects.removeRule(port, Set.of(A), RULE, EffectLedger.empty(), tx, feedback);
        c.that(outcome instanceof EffectOutcome.Rejected rejected && rejected.reason() == Reason.NOTHING_TO_REMOVE,
                "T5:nothing-to-remove-rejected");
        tx.commit();
        c.that(feedback.events().isEmpty(), "T5:no-feedback");
    }

    private void t6FinalityOnAbort(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx outer = Tx.open();
        Tx nested = outer.openNested();
        executor.apply(port, Set.of(A), RULE, EFFECTS, nested, feedback);
        nested.commit();
        outer.abort();
        c.equal(Set.of(), port.active(A), "T6:state-restored");
        c.that(feedback.events().isEmpty(), "T6:no-feedback-after-abort");
    }

    private void t7FinalityOnCommit(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx outer = Tx.open();
        Tx nested = outer.openNested();
        executor.apply(port, Set.of(A), RULE, EFFECTS, nested, feedback);
        nested.commit();
        c.that(feedback.events().isEmpty(), "T7:no-feedback-before-outer-commit");
        outer.commit();
        c.that(port.active(A).containsAll(IDS), "T7:active-after-commit");
        c.equalLong(1, feedback.events().size(), "T7:feedback-once");
    }

    private void t8LedgerReconcile(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        EffectLedger ledger = EffectLedger.empty();
        Tx apply = Tx.open();
        EffectOutcome applied = executor.apply(port, Set.of(A, B), RULE, EFFECTS, apply, feedback);
        EffectOutcome other = executor.apply(port, Set.of(A), OTHER_RULE,
                List.of(new EffectSpec.Attribute("minecraft:movement_speed", Op.MULTIPLY_TOTAL, -0.3, "example:heavy/speed")),
                apply, feedback);
        apply.commit();
        if (applied instanceof EffectOutcome.Committed committed
                && committed.receipt() instanceof Receipt.ApplyReceipt receipt) {
            ledger = ledger.withApplied(receipt);
        }
        if (other instanceof EffectOutcome.Committed committed
                && committed.receipt() instanceof Receipt.ApplyReceipt receipt) {
            ledger = ledger.withApplied(receipt);
        }
        List<Leak> afterApply = EffectLedger.reconcile(ledger, port, Set.of(A, B, OUTSIDE));
        c.that(afterApply.isEmpty(), "T8:no-leaks-after-apply " + afterApply);

        Tx remove = Tx.open();
        EffectOutcome removed = RuleEffects.removeRule(port, Set.of(A, B), RULE, ledger, remove, feedback);
        remove.commit();
        if (removed instanceof EffectOutcome.Committed committed
                && committed.receipt() instanceof Receipt.RemoveReceipt receipt) {
            ledger = ledger.withRemoved(receipt);
        }
        List<Leak> afterRemove = EffectLedger.reconcile(ledger, port, Set.of(A, B, OUTSIDE));
        c.that(afterRemove.isEmpty(), "T8:no-leaks-after-remove " + afterRemove);
        c.equal(Set.of("example:heavy/speed"), port.active(A), "T8:other-rule-untouched");
        c.equal(Set.of("example:heavy/speed"), ledger.allIds(A), "T8:ledger-other-rule");
    }

    private void t9AntiEvasion(Check c) {
        EffectPort port = freshPort();
        FeedbackSink feedback = new FeedbackSink();
        Tx tx = Tx.open();
        EffectOutcome outcome = executor.apply(port, Set.of(A), RULE, EFFECTS, tx, feedback);
        c.that(!(outcome instanceof EffectOutcome.Unsupported), "T9:no-evasion-unsupported");
        c.that(!(outcome instanceof EffectOutcome.Rejected), "T9:no-evasion-rejected");
        c.that(outcome instanceof EffectOutcome.Committed, "T9:committed-expected");
        tx.commit();
        c.that(port.active(A).containsAll(IDS), "T9:active-contains-ids");
    }
}
