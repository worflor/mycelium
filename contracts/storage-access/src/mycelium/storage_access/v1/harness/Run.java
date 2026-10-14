package mycelium.storage_access.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.storage_access.v1.fixtures.ExecutorVariants;
import mycelium.storage_access.v1.fixtures.FluidVariants;
import mycelium.storage_access.v1.fixtures.MemoryPort;
import mycelium.storage_access.v1.fixtures.MemoryWorld;
import mycelium.storage_access.v1.fixtures.PortVariants;
import mycelium.storage_access.v1.fixtures.SidedVariants;
import mycelium.storage_access.v1.model.MoveExecutor;
import mycelium.storage_access.v1.model.NativeFluidStore;
import mycelium.storage_access.v1.model.SlottedContainer;
import mycelium.storage_access.v1.model.StorageAccess;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class Run {
    private Run() {
    }

    private static ContractRun.Broken port(String name, Function<MemoryWorld, StoragePort> ports, String label) {
        return new ContractRun.Broken(name, () -> new StorageAccessContractTests(StorageAccess::move, ports).run(), label);
    }

    private static ContractRun.Broken sided(String name, BiFunction<SlottedContainer, StoreAddress, Store> adapter, String label) {
        return port(name, w -> new MemoryPort(w, MemoryPort.Adapters.REFERENCE.withSided(adapter)), label);
    }

    private static ContractRun.Broken fluids(String name, Function<NativeFluidStore, Store> seam, String label) {
        return port(name, w -> new MemoryPort(w, MemoryPort.Adapters.REFERENCE.withFluids(seam)), label);
    }

    private static ContractRun.Broken executor(String name, MoveExecutor executor, String label) {
        return new ContractRun.Broken(name, () -> new StorageAccessContractTests(executor, MemoryPort::new).run(), label);
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("storage.access@1",
                () -> new StorageAccessContractTests(StorageAccess::move, MemoryPort::new).run(),
                List.of(
                        port("NoOpPort", PortVariants::noOp, "P1:moved-min"),
                        executor("TimidExecutor", ExecutorVariants.timid(), "P2:no-evasion"),
                        executor("SelfMoveExecutor", ExecutorVariants.selfMove(), "E1:outcome"),
                        port("MirroredFacePort", PortVariants::mirroredFace, "S1:face-kept"),
                        port("LevelBlindPort", PortVariants::levelBlind, "S1:level-kept"),
                        port("PhantomPort", PortVariants::phantom, "S1:absent"),
                        port("CachingPort", PortVariants::caching, "S1:fresh-after-replace"),
                        sided("CommittingSimulationAdapter", SidedVariants.committingSimulation(), "S2:simulate-mutates-nothing"),
                        sided("CompactingUndoAdapter", SidedVariants.compactingUndo(), "S3:abort-restores"),
                        sided("SidelessAdapter", SidedVariants.sideless(), "S4:takes-only-allowed"),
                        sided("SlotListOnlyAdapter", SidedVariants.slotListOnly(), "S4:places-only-allowed"),
                        fluids("RoundingFluidSeam", FluidVariants.rounding(), "S5:native-conserved"),
                        fluids("FlooringFluidSeam", FluidVariants.flooring(), "S5:remainder-refused"),
                        sided("StaleHandleAdapter", SidedVariants.staleHandle(), "S6:vanished-moves-nothing"),
                        executor("UnwatchfulExecutor", ExecutorVariants.unwatchful(), "S6:vanish-aborts"),
                        sided("OverstackingAdapter", SidedVariants.overstacking(), "S7:within-capacity"),
                        sided("NonParticipatingInsertAdapter", SidedVariants.nonParticipatingInsert(), "A1:neither-unless-moved"),
                        executor("SplitCommitExecutor", ExecutorVariants.splitCommit(), "A1:neither-unless-moved"),
                        executor("ClaimingExecutor", ExecutorVariants.claiming(), "A1:moved-both"),
                        executor("NoReleaseExecutor", ExecutorVariants.noRelease(), "A1:remainder-released"),
                        executor("EarlyFeedbackExecutor", ExecutorVariants.earlyFeedback(), "F1:feedback-at-outer-commit"),
                        executor("SilentExecutor", ExecutorVariants.silent(), "F1:feedback-once")));
        System.exit(exit);
    }
}
