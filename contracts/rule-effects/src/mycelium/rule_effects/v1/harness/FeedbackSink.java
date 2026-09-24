package mycelium.rule_effects.v1.harness;

import mycelium.rule_effects.v1.model.EffectFeedback;
import mycelium.rule_effects.v1.model.Receipt;

import java.util.ArrayList;
import java.util.List;

public final class FeedbackSink implements EffectFeedback {
    private final List<Receipt> events = new ArrayList<>();

    @Override
    public void applied(Receipt.ApplyReceipt receipt) {
        events.add(receipt);
    }

    @Override
    public void removed(Receipt.RemoveReceipt receipt) {
        events.add(receipt);
    }

    public List<Receipt> events() {
        return List.copyOf(events);
    }
}
