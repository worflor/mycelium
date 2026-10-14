package mycelium.storage_access.v1.model;

/** Presentation and bookkeeping (a sound, a stock index): receives each receipt once, at the outermost commit. */
public interface MoveFeedback {
    void moved(MoveReceipt receipt);
}
