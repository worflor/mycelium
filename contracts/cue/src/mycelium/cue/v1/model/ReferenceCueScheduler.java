package mycelium.cue.v1.model;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the cue's key through a {@link ProfileMap}: a direct mapping wins; a missing mapping falls back
 * to the profile's declared fallback if one exists; a missing mapping with no fallback is
 * {@code Unsupported}, never a silent no-op. On resolution, the emit is deferred to the outermost commit
 * and the outcome is {@code Scheduled} with the resolved cue.
 */
public final class ReferenceCueScheduler implements CueScheduler {
    private final ProfileMap profileMap;

    public ReferenceCueScheduler(ProfileMap profileMap) {
        this.profileMap = Objects.requireNonNull(profileMap, "profileMap");
    }

    @Override
    public CueOutcome schedule(Tx tx, Receipt receipt, Cue cue, CueSink sink) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(cue, "cue");
        Objects.requireNonNull(sink, "sink");

        ProfileId profileId = new ProfileId(cue.profile());
        Optional<String> resolvedKey = profileMap.resolve(profileId, cue.key());
        if (resolvedKey.isEmpty()) {
            if (!profileMap.hasFallback(profileId)) {
                return new CueOutcome.Unsupported(
                        "no mapping for " + cue.key() + " on profile " + cue.profile() + " and no fallback");
            }
            resolvedKey = profileMap.resolve(profileId, ProfileMap.FALLBACK_KEY);
            if (resolvedKey.isEmpty()) {
                return new CueOutcome.Unsupported(
                        "profile " + cue.profile() + " declares a fallback but does not resolve it");
            }
        }

        Cue resolved = new Cue(cue.kind(), cue.profile(), resolvedKey.get());
        tx.onOuterCommit(() -> sink.emit(resolved));
        return new CueOutcome.Scheduled(resolved);
    }
}
