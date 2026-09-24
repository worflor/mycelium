package mycelium.flow.v1.harness;

import mycelium.flow.v1.model.Feedback;
import mycelium.flow.v1.model.Receipt;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements Feedback {
    private final List<Receipt> events = new ArrayList<>();

    @Override
    public void transferred(Receipt receipt) {
        events.add(receipt);
    }

    public List<Receipt> events() {
        return List.copyOf(events);
    }
}
