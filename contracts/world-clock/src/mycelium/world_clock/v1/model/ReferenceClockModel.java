package mycelium.world_clock.v1.model;

import java.util.Optional;

/**
 * The reference {@link ClockModel}: 26.3's arithmetic, class for class (SPEC.md quotes the methods it follows).
 *
 * <p>The fraction is a float and each tick is one float addition, floored: exactly {@code ServerClockInstance.tick}. A
 * client extrapolates in double and stores a float, exactly {@code ClientClockManager.tick}. The two agree to within one
 * tick, not always to the bit, as in the game (C5). The methods are not final: a fixture breaks one rule by overriding it.
 */
public class ReferenceClockModel implements ClockModel {

    @Override
    public ClockState tick(ClockState state, boolean advanceTime) {
        if (!advanceTime || state.paused()) {
            return state;
        }
        float partial = state.partialTick() + state.rate();
        int whole = GameMath.floor(partial);
        partial = partial - (float) whole;
        return new ClockState(state.totalTicks() + (long) whole, partial, state.rate(), state.paused());
    }

    @Override
    public ClockState set(ClockState state, long totalTicks) {
        return new ClockState(totalTicks, 0.0F, state.rate(), state.paused());
    }

    @Override
    public ClockState add(ClockState state, int ticks) {
        return state.withTotalTicks(Math.max(state.totalTicks() + (long) ticks, 0L));
    }

    @Override
    public ClockState pause(ClockState state, boolean paused) {
        return state.withPaused(paused);
    }

    @Override
    public ClockState rate(ClockState state, float rate) {
        return state.withRate(rate);
    }

    @Override
    public Move moveTo(ClockState state, Optional<Marker> marker) {
        if (!marker.isPresent()) {
            return new Move(MoveResult.NO_SUCH_MARKER, state);
        }
        Marker m = marker.get();
        if (occursAt(m, state.totalTicks())) {
            return new Move(MoveResult.NOT_MOVED, state);
        }
        return new Move(MoveResult.MOVED,
                new ClockState(resolveTimeToMoveTo(m, state.totalTicks()), 0.0F, state.rate(), state.paused()));
    }

    @Override
    public Move sleep(ClockState state, Optional<Marker> wakeMarker, boolean advanceTime) {
        if (!advanceTime) {
            return new Move(MoveResult.NOT_MOVED, state);
        }
        return moveTo(state, wakeMarker);
    }

    @Override
    public boolean occursAt(Marker marker, long totalTicks) {
        if (!marker.periodic()) {
            return marker.ticks() == totalTicks;
        }
        return marker.ticks() == totalTicks % marker.periodTicks().getAsInt();
    }

    @Override
    public long resolveTimeToMoveTo(Marker marker, long totalTicks) {
        if (!marker.periodic()) {
            return marker.ticks();
        }
        int period = marker.periodTicks().getAsInt();
        long inPeriod = totalTicks % period;
        long duration = marker.ticks() - inPeriod;
        if (duration <= 0) {
            duration += period;
        }
        return totalTicks + duration;
    }

    @Override
    public long repetitionCount(Marker marker, long totalTicks) {
        if (!marker.periodic()) {
            return totalTicks >= marker.ticks() ? 1L : 0L;
        }
        int period = marker.periodTicks().getAsInt();
        return totalTicks / period + (totalTicks % period >= marker.ticks() ? 1L : 0L);
    }

    @Override
    public long periodCount(Timeline timeline, long totalTicks) {
        if (!timeline.periodTicks().isPresent()) {
            return 0L;
        }
        return totalTicks / timeline.periodTicks().getAsInt();
    }

    @Override
    public long currentTicks(Timeline timeline, long totalTicks) {
        if (!timeline.periodTicks().isPresent()) {
            return totalTicks;
        }
        return totalTicks % timeline.periodTicks().getAsInt();
    }

    @Override
    public int phase(Timeline timeline, long totalTicks, int phases) {
        if (!timeline.periodTicks().isPresent()) {
            return 0;
        }
        return (int) (currentTicks(timeline, totalTicks) * phases / timeline.periodTicks().getAsInt());
    }

    @Override
    public NetworkState network(ClockState state, boolean advanceTime) {
        boolean still = state.paused() || !advanceTime;
        return new NetworkState(state.totalTicks(), state.partialTick(), still ? 0.0F : state.rate());
    }

    @Override
    public NetworkState extrapolate(NetworkState synced, long elapsedGameTicks) {
        double sum = (double) synced.partialTick() + (double) elapsedGameTicks * (double) synced.rate();
        long whole = (long) GameMath.floor(sum);
        float partial = (float) (sum - (double) whole);
        return new NetworkState(synced.totalTicks() + whole, partial, synced.rate());
    }
}
