package mycelium.state_lifecycle.v1.harness;

import mycelium.state_lifecycle.v1.fixtures.FakeHost;
import mycelium.state_lifecycle.v1.fixtures.MemoryHostStore;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;

import java.util.function.Supplier;

/** The three things the laws run against; the rejection matrix swaps one for a broken one. */
record Rig(Supplier<Crossings> engine, Supplier<MemoryHostStore<FakeHost>> store, RegistryCompiler compiler) {
}
