package mycelium.cue.v1.model;

public sealed interface CueOutcome {
    record Scheduled(Cue cue) implements CueOutcome {
        public Scheduled {
            if (cue == null) {
                throw new IllegalArgumentException("cue");
            }
        }
    }

    record Unsupported(String detail) implements CueOutcome {
    }
}
