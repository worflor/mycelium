package mycelium.world_copy.v1.model;

/** The sealed copy's shape: {@link WorldCopy#executeSealed} is the reference. */
@FunctionalInterface
public interface SealedCopyExecutor {
    CopyOutcome execute(SourceView source, TargetPort target, Region region, Transform transform, ExitPolicy exits,
                        SanitizePolicy sanitize, Budget budget, CopyTx tx, Feedback feedback);
}
