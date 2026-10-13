package mycelium.world_clock.v1.model;

import java.util.Optional;

/** Finds a clock's marker by id, across the timelines laid on that clock. */
@FunctionalInterface
public interface MarkerLookup {
    Optional<Marker> find(String markerId);
}
