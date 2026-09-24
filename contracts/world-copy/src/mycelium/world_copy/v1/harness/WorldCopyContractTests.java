package mycelium.world_copy.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.world_copy.v1.fixtures.MemorySource;
import mycelium.world_copy.v1.fixtures.MemoryTarget;
import mycelium.world_copy.v1.fixtures.OutOfRegionTarget;
import mycelium.world_copy.v1.fixtures.UnqualifiedTarget;
import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.Budget;
import mycelium.world_copy.v1.model.CopyExecutor;
import mycelium.world_copy.v1.model.CopyOutcome;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;
import mycelium.world_copy.v1.model.Reason;
import mycelium.world_copy.v1.model.Region;
import mycelium.world_copy.v1.model.SanitizePolicy;
import mycelium.world_copy.v1.model.TargetPort;
import mycelium.world_copy.v1.model.Transform;

import java.util.List;
import java.util.function.Supplier;

public final class WorldCopyContractTests {
    private static final BlockToken AIR = new BlockToken("air");
    private static final BlockToken STONE = new BlockToken("stone");
    private static final BlockToken TUFF = new BlockToken("tuff");
    private static final BlockToken CHEST = new BlockToken("chest");
    private static final BlockToken SIGN = new BlockToken("sign");
    private static final BlockToken OUTSIDE = new BlockToken("outside");
    private static final Budget GENEROUS = new Budget(10_000);

    private final CopyExecutor executor;
    private final Supplier<TargetPort> targetFactory;

    public WorldCopyContractTests(CopyExecutor executor, Supplier<TargetPort> targetFactory) {
        this.executor = executor;
        this.targetFactory = targetFactory;
    }

    public Check run() {
        Check check = new Check();
        t1IdentityCopy(check);
        t2SanitizeClears(check);
        t3BlockEntityPreserved(check);
        t4NoWriteOutsideRegion(check);
        t5BudgetExceeded(check);
        t6FinalityOnAbort(check);
        t7FinalityOnCommit(check);
        t8TransformApplied(check);
        t9SeededIdentity(check);
        t10UnsupportedTrait(check);
        t11AntiEvasion(check);
        t12EmptyRegion(check);
        return check;
    }

    /** A varied source over the region: token depends on position so identity is a real test. */
    private static MemorySource patterned(Region region) {
        MemorySource source = new MemorySource(AIR);
        for (Pos p : region.positions()) {
            int k = Math.floorMod(p.x() * 7 + p.y() * 3 + p.z(), 5);
            source.put(p, new BlockToken("t" + k), false);
        }
        return source;
    }

    private static boolean targetEqualsSource(TargetPort target, MemorySource source, Region region) {
        for (Pos p : region.positions()) {
            if (!target.token(p).equals(source.token(p))) {
                return false;
            }
        }
        return true;
    }

    private static boolean allVoid(TargetPort target, Region region) {
        for (Pos p : region.positions()) {
            if (!target.token(p).equals(MemoryTarget.VOID)) {
                return false;
            }
        }
        return true;
    }

    private void t1IdentityCopy(Check c) {
        Region region = Region.cube(new Pos(0, 64, 0), 3, 2, 3);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                GENEROUS, tx, feedback);
        c.that(outcome instanceof CopyOutcome.Committed, "T1:outcome-committed");
        if (outcome instanceof CopyOutcome.Committed committed) {
            c.that(committed.receipt().writes() == 18, "T1:receipt-writes-18");
        }
        tx.commit();
        c.that(targetEqualsSource(target, source, region), "T1:target-equals-source");
        c.that(feedback.events().size() == 1, "T1:feedback-once");
    }

    private void t2SanitizeClears(Check c) {
        Region region = Region.cube(new Pos(10, 0, 10), 2, 1, 2);
        MemorySource source = new MemorySource(AIR).fill(region, STONE);
        Pos chest = new Pos(10, 0, 10);
        Pos sign = new Pos(11, 0, 11);
        source.put(chest, CHEST, true).put(sign, SIGN, true);
        TargetPort target = targetFactory.get();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                GENEROUS, tx, new FeedbackSink());
        tx.commit();
        c.that(outcome instanceof CopyOutcome.Committed committed && committed.receipt().sanitized() == 2,
                "T2:receipt-sanitized-2");
        c.that(!target.hasBlockEntity(chest) && !target.hasBlockEntity(sign), "T2:sanitized-cleared");
        c.that(target.token(chest).equals(CHEST) && target.token(sign).equals(SIGN), "T2:tokens-kept");
    }

    private void t3BlockEntityPreserved(Check c) {
        Region region = Region.cube(new Pos(10, 0, 10), 2, 1, 2);
        MemorySource source = new MemorySource(AIR).fill(region, STONE);
        Pos chest = new Pos(10, 0, 10);
        Pos sign = new Pos(11, 0, 11);
        source.put(chest, CHEST, true).put(sign, SIGN, true);
        TargetPort target = targetFactory.get();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.NONE,
                GENEROUS, tx, new FeedbackSink());
        tx.commit();
        c.that(outcome instanceof CopyOutcome.Committed committed && committed.receipt().sanitized() == 0,
                "T3:receipt-sanitized-0");
        c.that(target.hasBlockEntity(chest) && target.hasBlockEntity(sign), "T3:block-entity-preserved");
    }

    private void t4NoWriteOutsideRegion(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 2, 2, 2);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        List<Pos> outside = List.of(new Pos(-1, 0, 0), new Pos(2, 0, 0), new Pos(0, -1, 0), new Pos(0, 2, 0),
                new Pos(0, 0, -1), new Pos(0, 0, 2), OutOfRegionTarget.STRAY);
        if (target instanceof MemoryTarget m) {
            for (Pos p : outside) {
                m.seed(p, OUTSIDE, false);
            }
        } else {
            // broken targets forward to a MemoryTarget; seed through the port under a throwaway tx
            CopyTx seedTx = CopyTx.open();
            for (Pos p : outside) {
                target.set(p, OUTSIDE, false, seedTx);
            }
            seedTx.commit();
        }
        CopyTx tx = CopyTx.open();
        executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL, GENEROUS, tx,
                new FeedbackSink());
        tx.commit();
        boolean untouched = true;
        for (Pos p : outside) {
            untouched &= target.token(p).equals(OUTSIDE);
        }
        c.that(untouched, "T4:no-write-outside-region");
        c.that(targetEqualsSource(target, source, region), "T4:target-equals-source");
    }

    private void t5BudgetExceeded(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 4, 4, 4);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                new Budget(10), tx, feedback);
        c.that(outcome instanceof CopyOutcome.Rejected rejected && rejected.reason() == Reason.BUDGET_EXCEEDED,
                "T5:budget-rejected");
        tx.commit();
        c.that(allVoid(target, region), "T5:no-partial-state");
        c.that(feedback.events().isEmpty(), "T5:no-feedback");
    }

    private void t6FinalityOnAbort(Check c) {
        Region region = Region.cube(new Pos(5, 5, 5), 2, 2, 2);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        CopyTx outer = CopyTx.open();
        CopyTx nested = outer.openNested();
        executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL, GENEROUS, nested, feedback);
        nested.commit();
        outer.abort();
        c.that(allVoid(target, region), "T6:state-restored");
        c.that(feedback.events().isEmpty(), "T6:no-feedback-after-abort");
    }

    private void t7FinalityOnCommit(Check c) {
        Region region = Region.cube(new Pos(5, 5, 5), 2, 2, 2);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        CopyTx outer = CopyTx.open();
        CopyTx nested = outer.openNested();
        executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL, GENEROUS, nested, feedback);
        nested.commit();
        c.that(feedback.events().isEmpty(), "T7:no-feedback-before-outer-commit");
        outer.commit();
        c.that(targetEqualsSource(target, source, region), "T7:target-equals-source");
        c.that(feedback.events().size() == 1, "T7:feedback-once");
    }

    private void t8TransformApplied(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 2, 1, 2);
        MemorySource source = new MemorySource(AIR).fill(region, STONE);
        Pos keep = new Pos(1, 0, 1);
        source.put(keep, CHEST, false);
        TargetPort target = targetFactory.get();
        Transform stoneToTuff = (pos, token) -> token.equals(STONE) ? TUFF : token;
        CopyTx tx = CopyTx.open();
        executor.execute(source, target, region, stoneToTuff, SanitizePolicy.ALL, GENEROUS, tx, new FeedbackSink());
        tx.commit();
        boolean applied = true;
        for (Pos p : region.positions()) {
            BlockToken expected = p.equals(keep) ? CHEST : TUFF;
            applied &= target.token(p).equals(expected);
        }
        c.that(applied, "T8:transform-applied");
    }

    private void t9SeededIdentity(Check c) {
        boolean identity = true;
        boolean counts = true;
        boolean entities = true;
        for (long seed = 1; seed <= 5; seed++) {
            Seeded r = new Seeded(seed);
            Region region = Region.cube(new Pos(r.nextInt(-8, 9), r.nextInt(-8, 9), r.nextInt(-8, 9)),
                    r.nextInt(1, 5), r.nextInt(1, 4), r.nextInt(1, 5));
            MemorySource source = new MemorySource(AIR);
            for (Pos p : region.positions()) {
                source.put(p, new BlockToken("s" + r.nextInt(4)), r.nextInt(4) == 0);
            }
            TargetPort target = targetFactory.get();
            CopyTx tx = CopyTx.open();
            CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.NONE,
                    GENEROUS, tx, new FeedbackSink());
            tx.commit();
            identity &= targetEqualsSource(target, source, region);
            counts &= outcome instanceof CopyOutcome.Committed committed
                    && committed.receipt().writes() == region.size();
            for (Pos p : region.positions()) {
                entities &= target.hasBlockEntity(p) == source.hasBlockEntity(p);
            }
        }
        c.that(identity, "T9:seeded-identity");
        c.that(counts, "T9:seeded-write-count");
        c.that(entities, "T9:seeded-block-entities");
    }

    private void t10UnsupportedTrait(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 2, 1, 1);
        MemorySource source = patterned(region);
        UnqualifiedTarget target = new UnqualifiedTarget();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                GENEROUS, tx, new FeedbackSink());
        c.that(outcome instanceof CopyOutcome.Unsupported, "T10:unsupported-outcome");
        tx.commit();
        c.that(target.memory().writtenPositions() == 0, "T10:no-mutation");
    }

    private void t11AntiEvasion(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 2, 2, 2);
        MemorySource source = patterned(region);
        TargetPort target = targetFactory.get();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                GENEROUS, tx, new FeedbackSink());
        c.that(!(outcome instanceof CopyOutcome.Unsupported), "T11:no-evasion-unsupported");
        c.that(!(outcome instanceof CopyOutcome.Rejected), "T11:no-evasion-rejected");
        c.that(outcome instanceof CopyOutcome.Committed, "T11:committed-expected");
        tx.commit();
    }

    private void t12EmptyRegion(Check c) {
        Region region = Region.cube(new Pos(0, 0, 0), 0, 3, 3);
        MemorySource source = new MemorySource(AIR);
        TargetPort target = targetFactory.get();
        FeedbackSink feedback = new FeedbackSink();
        CopyTx tx = CopyTx.open();
        CopyOutcome outcome = executor.execute(source, target, region, Transform.IDENTITY, SanitizePolicy.ALL,
                GENEROUS, tx, feedback);
        c.that(outcome instanceof CopyOutcome.Rejected rejected && rejected.reason() == Reason.EMPTY_REGION,
                "T12:empty-region-rejected");
        tx.commit();
        c.that(feedback.events().isEmpty(), "T12:no-feedback");
    }
}
