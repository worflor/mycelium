package mycelium.rule_effects.v1.model;

/** Presentation channel. Receives receipts only at the outermost commit. */
public interface EffectFeedback {
    void applied(Receipt.ApplyReceipt receipt);

    void removed(Receipt.RemoveReceipt receipt);
}
