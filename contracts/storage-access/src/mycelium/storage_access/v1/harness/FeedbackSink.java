package mycelium.storage_access.v1.harness;

import mycelium.storage_access.v1.model.MoveFeedback;
import mycelium.storage_access.v1.model.MoveReceipt;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements MoveFeedback {
    private final List<MoveReceipt> events = new ArrayList<>();

    @Override
    public void moved(MoveReceipt receipt) {
        events.add(receipt);
    }

    public List<MoveReceipt> events() {
        return List.copyOf(events);
    }
}
