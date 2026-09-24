package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
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

/**
 * Deliberately incorrect: forgets the source's exits, then transforms, so a transform that makes an exit (a
 * mutation pass, a drifted palette) opens one anyway (L8: the exit policy applies after the transform).
 */
public final class SealFirstExecutor {
    private SealFirstExecutor() {
    }

    public static CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform, ExitPolicy exits,
                                      SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback) {
        Transform sealedFirst = (pos, token) -> transform.apply(pos, exits.isExit(token) ? exits.replacement(token) : token);
        return WorldCopy.execute(source, target, region, sealedFirst, sanitize, budget, tx, feedback);
    }
}
