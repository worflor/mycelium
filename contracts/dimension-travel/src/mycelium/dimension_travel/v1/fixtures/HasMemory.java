package mycelium.dimension_travel.v1.fixtures;

/** Implemented by every fixture that forwards to a backing {@link MemoryTravelPort}, for test-side seeding. */
public interface HasMemory {
    MemoryTravelPort memory();
}
