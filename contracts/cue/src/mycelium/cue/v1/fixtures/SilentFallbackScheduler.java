package mycelium.cue.v1.fixtures;

import mycelium.cue.v1.model.Cue;
import mycelium.cue.v1.model.CueOutcome;
import mycelium.cue.v1.model.CueScheduler;
import mycelium.cue.v1.model.CueSink;
import mycelium.cue.v1.model.ProfileId;
import mycelium.cue.v1.model.ProfileMap;
import mycelium.cue.v1.model.Receipt;
import mycelium.cue.v1.model.Tx;

import java.util.Optional;

/** When a mapping is missing and there is no fallback, returns {@code Scheduled} anyway without ever
 * emitting: violates L4 (should be {@code Unsupported}, never a silent skip) and P1. */
public final class SilentFallbackScheduler implements CueScheduler {
    private final ProfileMap profileMap;

    public SilentFallbackScheduler(ProfileMap profileMap) {
        this.profileMap = profileMap;
    }

    @Override
    public CueOutcome schedule(Tx tx, Receipt receipt, Cue cue, CueSink sink) {
        ProfileId profileId = new ProfileId(cue.profile());
        Optional<String> resolvedKey = profileMap.resolve(profileId, cue.key());
        if (resolvedKey.isEmpty()) {
            if (!profileMap.hasFallback(profileId)) {
                // BUG: silently reports success instead of Unsupported, and never schedules an emission.
                return new CueOutcome.Scheduled(cue);
            }
            resolvedKey = profileMap.resolve(profileId, ProfileMap.FALLBACK_KEY);
        }
        Cue resolved = new Cue(cue.kind(), cue.profile(), resolvedKey.orElse(cue.key()));
        tx.onOuterCommit(() -> sink.emit(resolved));
        return new CueOutcome.Scheduled(resolved);
    }
}
