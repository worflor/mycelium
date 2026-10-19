package mycelium.world_replay.v1.model;

import java.util.Objects;

/** Immutable, validated operation data; cursor creation shares only this private immutable run array. */
public final class ReplayProgram implements ReplaySource {
    private final int[] runs;
    private final int volume;
    private final int runCount;

    public ReplayProgram(int[] runs, int volume, int paletteCount) {
        Objects.requireNonNull(runs, "runs");
        if (volume < 1) throw new IllegalArgumentException("Replay volume must be positive.");
        if (paletteCount < 1) throw new IllegalArgumentException("Replay palette must not be empty.");
        if (runs.length % 3 != 0) throw new IllegalArgumentException("Replay runs must contain start/length/palette triples.");
        this.runCount = runs.length / 3;
        if (runCount > volume) throw new IllegalArgumentException("Replay has more runs than positions in its volume.");
        this.runs = runs.clone();
        this.volume = volume;
        validate(paletteCount);
    }

    /** A defensive snapshot for inspection and compatibility; traversal never uses this copy. */
    public int[] copyRuns() {
        return runs.clone();
    }

    @Override
    public ReplayCursor cursor() {
        return new ReplayCursor(this);
    }

    int runCount() { return runCount; }
    int start(int index) { return runs[index * 3]; }
    int length(int index) { return runs[index * 3 + 1]; }
    int paletteIndex(int index) { return runs[index * 3 + 2]; }

    private void validate(int paletteCount) {
        long previousEnd = 0;
        for (int tuple = 0; tuple < runs.length; tuple += 3) {
            int start = runs[tuple];
            int length = runs[tuple + 1];
            int paletteIndex = runs[tuple + 2];
            long end = (long) start + length;
            if (start < previousEnd || length < 1 || end > volume
                    || paletteIndex < 0 || paletteIndex >= paletteCount) {
                throw new IllegalArgumentException("Replay run is outside its volume or palette, or overlaps an earlier run.");
            }
            previousEnd = end;
        }
    }
}
