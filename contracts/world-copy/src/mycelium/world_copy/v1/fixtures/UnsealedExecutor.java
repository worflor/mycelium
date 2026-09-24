package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.Budget;
import mycelium.world_copy.v1.model.CopyOutcome;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.ExitPolicy;
import mycelium.world_copy.v1.model.Feedback;
import mycelium.world_copy.v1.model.Region;
import mycelium.world_copy.v1.model.SanitizePolicy;
import mycelium.world_copy.v1.model.SourceView;
import mycelium.world_copy.v1.model.TargetPort;
import mycelium.world_copy.v1.model.Transform;
import mycelium.world_copy.v1.model.WorldCopy;

/** Deliberately incorrect: takes an exit policy and ignores it; the copy keeps its portals (L8). */
public final class UnsealedExecutor {
    private UnsealedExecutor() {
    }

    public static CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform, ExitPolicy exits,
                                      SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback) {
        return WorldCopy.execute(source, target, region, transform, sanitize, budget, tx, feedback);
    }
}
