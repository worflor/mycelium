package mycelium.cue.v1.fixtures;

import mycelium.cue.v1.model.Cue;
import mycelium.cue.v1.model.CueOutcome;
import mycelium.cue.v1.model.CueScheduler;
import mycelium.cue.v1.model.CueSink;
import mycelium.cue.v1.model.ProfileMap;
import mycelium.cue.v1.model.Receipt;
import mycelium.cue.v1.model.Tx;

/** Never emits, always reports Scheduled: honest inspection is not even attempted. Violates P1. */
public final class NoOpScheduler implements CueScheduler {
    public NoOpScheduler(ProfileMap profileMap) {
        // ignored: this fixture never consults the profile map
    }

    @Override
    public CueOutcome schedule(Tx tx, Receipt receipt, Cue cue, CueSink sink) {
        return new CueOutcome.Scheduled(cue);
    }
}
