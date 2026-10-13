package mycelium.world_clock.v1.model;

import java.util.Optional;

/** A command's result: the clock's new state, and, for a move to a marker, what the move did. */
public record Applied(ClockState state, Optional<MoveResult> move) {
    public Applied {
        if (state == null || move == null) {
            throw new IllegalArgumentException("an applied command needs a state and a (possibly empty) move");
        }
    }
}
