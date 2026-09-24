package mycelium.cue.v1.harness;

import mycelium.cue.v1.model.Cue;
import mycelium.cue.v1.model.CueSink;

import java.util.ArrayList;
import java.util.List;

public final class MemorySink implements CueSink {
    private final List<Cue> events = new ArrayList<>();

    @Override
    public void emit(Cue cue) {
        events.add(cue);
    }

    public List<Cue> events() {
        return List.copyOf(events);
    }
}
