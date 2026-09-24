package mycelium.player_state.v1.harness;

import mycelium.player_state.v1.model.Feedback;
import mycelium.player_state.v1.model.StateReceipt;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements Feedback {
    private final List<StateReceipt> events = new ArrayList<>();

    @Override
    public void written(StateReceipt receipt) {
        events.add(receipt);
    }

    public List<StateReceipt> events() {
        return List.copyOf(events);
    }
}
