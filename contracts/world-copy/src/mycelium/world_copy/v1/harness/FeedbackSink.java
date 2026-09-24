package mycelium.world_copy.v1.harness;

import mycelium.world_copy.v1.model.CopyReceipt;
import mycelium.world_copy.v1.model.Feedback;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements Feedback {
    private final List<CopyReceipt> events = new ArrayList<>();

    @Override
    public void copied(CopyReceipt receipt) {
        events.add(receipt);
    }

    public List<CopyReceipt> events() {
        return List.copyOf(events);
    }
}
