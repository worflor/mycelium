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

/** Defers the emit twice for one schedule call: violates L2 (exactly one cue per receipt). */
public final class DoubleCueScheduler implements CueScheduler {
    private final ProfileMap profileMap;

    public DoubleCueScheduler(ProfileMap profileMap) {
        this.profileMap = profileMap;
    }

    @Override
    public CueOutcome schedule(Tx tx, Receipt receipt, Cue cue, CueSink sink) {
        ProfileId profileId = new ProfileId(cue.profile());
        Optional<String> resolvedKey = profileMap.resolve(profileId, cue.key());
        if (resolvedKey.isEmpty()) {
            if (!profileMap.hasFallback(profileId)) {
                return new CueOutcome.Unsupported("no mapping and no fallback");
            }
            resolvedKey = profileMap.resolve(profileId, ProfileMap.FALLBACK_KEY);
        }
        Cue resolved = new Cue(cue.kind(), cue.profile(), resolvedKey.orElse(cue.key()));
        tx.onOuterCommit(() -> sink.emit(resolved));
        tx.onOuterCommit(() -> sink.emit(resolved));
        return new CueOutcome.Scheduled(resolved);
    }
}
