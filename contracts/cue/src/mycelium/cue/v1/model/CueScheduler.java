package mycelium.cue.v1.model;

public interface CueScheduler {
    CueOutcome schedule(Tx tx, Receipt receipt, Cue cue, CueSink sink);
}
