package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.Budget;
import mycelium.world_copy.v1.model.CopyOutcome;
import mycelium.world_copy.v1.model.CopyPlan;
import mycelium.world_copy.v1.model.CopyReceipt;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Feedback;
import mycelium.world_copy.v1.model.Pos;
import mycelium.world_copy.v1.model.Reason;
import mycelium.world_copy.v1.model.Region;
import mycelium.world_copy.v1.model.SanitizePolicy;
import mycelium.world_copy.v1.model.SourceView;
import mycelium.world_copy.v1.model.TargetPort;
import mycelium.world_copy.v1.model.Transform;
import mycelium.world_copy.v1.model.WorldCopy;
import mycelium.world_copy.v1.model.Write;

/** Ignores the budget: performs every write and claims success. */
public final class OverrunExecutor {
    private OverrunExecutor() {
    }

    public static CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform,
                                      SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback) {
        if (!target.traits().containsAll(WorldCopy.REQUIRED_TRAITS)) {
            return new CopyOutcome.Unsupported("target lacks required traits");
        }
        if (region.size() == 0) {
            return new CopyOutcome.Rejected(Reason.EMPTY_REGION);
        }
        CopyPlan plan = WorldCopy.plan(source, region, transform, sanitize);
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
