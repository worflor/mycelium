package mycelium.world_copy.v1.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reference executor for world.copy@1: plan the whole region first, then apply under the transaction. */
public final class WorldCopy {
    public static final Set<Trait> REQUIRED_TRAITS = Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.BLOCK_ENTITY_CONTROL);

    private WorldCopy() {
    }

    /** Pure planning: reads the source, applies the transform, decides sanitize positions. No mutation. */
    public static CopyPlan plan(SourceView source, Region region, Transform transform, SanitizePolicy sanitize) {
        List<Write> writes = new ArrayList<>();
        List<Pos> toClear = new ArrayList<>();
        for (Pos pos : region.positions()) {
            BlockToken sourceToken = source.token(pos);
            boolean blockEntity = source.hasBlockEntity(pos);
            BlockToken token = Objects.requireNonNull(transform.apply(pos, sourceToken), "transform returned null");
            writes.add(new Write(pos, token, blockEntity));
            if (blockEntity && sanitize.clear(pos, token)) {
                toClear.add(pos);
            }
        }
        return new CopyPlan(writes, toClear);
    }

    public static CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform,
                                      SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(transform, "transform");
        Objects.requireNonNull(sanitize, "sanitize");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (!target.traits().containsAll(REQUIRED_TRAITS)) {
            return new CopyOutcome.Unsupported("target lacks required traits");
        }
        if (region.size() == 0) {
            return new CopyOutcome.Rejected(Reason.EMPTY_REGION);
        }

        CopyPlan plan = plan(source, region, transform, sanitize);
        if (plan.writes().size() > budget.maxWritesPerCall()) {
            return new CopyOutcome.Rejected(Reason.BUDGET_EXCEEDED);
        }

        for (Write write : plan.writes()) {
            target.set(write.pos(), write.token(), write.blockEntity(), tx);
        }
        for (Pos pos : plan.sanitize()) {
            target.clearBlockEntity(pos, tx);
        }

        CopyReceipt receipt = new CopyReceipt(plan.writes().size(), plan.sanitize().size());
        tx.onOuterCommit(() -> feedback.copied(receipt));
        return new CopyOutcome.Committed(receipt);
    }
}
