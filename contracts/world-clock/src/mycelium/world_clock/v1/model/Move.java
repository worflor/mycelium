package mycelium.world_clock.v1.model;

/** The outcome of moving a clock to a marker: what happened, and the clock's state afterwards. */
public record Move(MoveResult result, ClockState state) {
    public Move {
        if (result == null || state == null) {
            throw new IllegalArgumentException("a move needs a result and a state");
        }
    }
}
