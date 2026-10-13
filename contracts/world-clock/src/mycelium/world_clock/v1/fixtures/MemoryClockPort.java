package mycelium.world_clock.v1.fixtures;

import mycelium.world_clock.v1.model.Applied;
import mycelium.world_clock.v1.model.ClockCommand;
import mycelium.world_clock.v1.model.ClockId;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockState;
import mycelium.world_clock.v1.model.ClockSync;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.Marker;
import mycelium.world_clock.v1.model.Move;
import mycelium.world_clock.v1.model.NetworkState;
import mycelium.world_clock.v1.model.StandardTimelines;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The in-memory reference {@link ClockPort}: every clock's state in a map, every rule from a {@link ClockModel}. It is
 * what an emulated bridge is before it is saved data, and what a native bridge is checked against. Not thread-safe.
 */
public class MemoryClockPort implements ClockPort {
    protected final ClockModel model;
    protected final ClockWorld world;
    protected final Map<ClockId, ClockState> states = new LinkedHashMap<>();
    protected long gameTime;

    public MemoryClockPort(ClockModel model, ClockWorld world) {
        this.model = model;
        this.world = world;
        for (ClockId clock : world.clocks()) {
            states.put(clock, ClockState.start());
        }
    }

    @Override
    public ClockWorld world() {
        return world;
    }

    @Override
    public long gameTime() {
        return gameTime;
    }

    @Override
    public ClockState state(ClockId clock) {
        ClockState s = states.get(clock);
        if (s == null) {
            throw new IllegalArgumentException("no clock " + clock);
        }
        return s;
    }

    @Override
    public Applied apply(ClockId clock, ClockCommand command) {
        Applied applied = model.apply(state(clock), command, id -> world.marker(clock, id));
        states.put(clock, applied.state());
        return applied;
    }

    @Override
    public void tick(boolean advanceTime) {
        gameTime++;
        for (Map.Entry<ClockId, ClockState> e : states.entrySet()) {
            e.setValue(model.tick(e.getValue(), advanceTime));
        }
    }

    @Override
    public Optional<Applied> sleep(String dimension, boolean advanceTime) {
        Optional<ClockId> clock = world.defaultClock(dimension);
        if (!clock.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(sleepClock(clock.get(), advanceTime));
    }

    /** Sleep moves one clock to its wake marker, if it has one. */
    protected Applied sleepClock(ClockId clock, boolean advanceTime) {
        Optional<Marker> wake = world.marker(clock, StandardTimelines.WAKE_UP_FROM_SLEEP);
        Move move = model.sleep(state(clock), wake, advanceTime);
        states.put(clock, move.state());
        return new Applied(move.state(), Optional.of(move.result()));
    }

    @Override
    public ClockSync sync(boolean advanceTime) {
        Map<ClockId, NetworkState> all = new LinkedHashMap<>();
        for (Map.Entry<ClockId, ClockState> e : states.entrySet()) {
            all.put(e.getKey(), model.network(e.getValue(), advanceTime));
        }
        return new ClockSync(gameTime, all);
    }

    @Override
    public ClockSync sync(ClockId clock, boolean advanceTime) {
        Map<ClockId, NetworkState> one = new LinkedHashMap<>();
        one.put(clock, model.network(state(clock), advanceTime));
        return new ClockSync(gameTime, one);
    }
}
