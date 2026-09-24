package mycelium.world_copy.v1.model;

@FunctionalInterface
public interface CopyExecutor {
    CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform,
                        SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback);
}
