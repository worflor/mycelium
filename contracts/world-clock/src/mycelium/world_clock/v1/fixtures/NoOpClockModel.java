package mycelium.world_clock.v1.fixtures;

import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.MoveResult;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.Timeline;

import java.util.Optional;

/**
 * Does nothing: time never passes, no command changes a clock, no marker ever occurs, every period count is zero. It
 * breaks no rule about moving the wrong way (it never moves), which is why it must be in the matrix: C1 says a running
 * clock advances.
 */
public final class NoOpClockModel implements ClockModel {
    @Override
    public ClockState tick(ClockState state, boolean advanceTime) {
        return state;
    }

    @Override
    public ClockState set(ClockState state, long totalTicks) {
        return state;
    }

    @Override
    public ClockState add(ClockState state, int ticks) {
        return state;
    }

    @Override
    public ClockState pause(ClockState state, boolean paused) {
        return state;
    }

    @Override
    public ClockState rate(ClockState state, float rate) {
        return state;
    }

    @Override
    public Move moveTo(ClockState state, Optional<Marker> marker) {
        return new Move(MoveResult.NOT_MOVED, state);
    }

    @Override
    public Move sleep(ClockState state, Optional<Marker> wakeMarker, boolean advanceTime) {
        return new Move(MoveResult.NOT_MOVED, state);
    }

    @Override
    public boolean occursAt(Marker marker, long totalTicks) {
        return false;
    }

    @Override
    public long resolveTimeToMoveTo(Marker marker, long totalTicks) {
        return totalTicks;
    }

    @Override
    public long repetitionCount(Marker marker, long totalTicks) {
        return 0L;
    }

    @Override
    public long periodCount(Timeline timeline, long totalTicks) {
        return 0L;
    }

    @Override
    public long currentTicks(Timeline timeline, long totalTicks) {
        return 0L;
    }

    @Override
    public int phase(Timeline timeline, long totalTicks, int phases) {
        return 0;
    }

    @Override
    public NetworkState network(ClockState state, boolean advanceTime) {
        return new NetworkState(state.totalTicks(), state.partialTick(), 0.0F);
    }

    @Override
    public NetworkState extrapolate(NetworkState synced, long elapsedGameTicks) {
        return synced;
    }
}
