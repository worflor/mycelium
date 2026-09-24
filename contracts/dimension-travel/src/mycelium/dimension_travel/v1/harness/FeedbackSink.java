package mycelium.dimension_travel.v1.harness;

import mycelium.dimension_travel.v1.model.Feedback;
import mycelium.dimension_travel.v1.model.TravelReceipt;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements Feedback {
    private final List<TravelReceipt> events = new ArrayList<>();

    @Override
    public void traveled(TravelReceipt receipt) {
        events.add(receipt);
    }

    public List<TravelReceipt> events() {
        return List.copyOf(events);
    }
}
