package mycelium.world_copy.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.world_copy.v1.fixtures.LeakyTarget;
import mycelium.world_copy.v1.fixtures.LossyTarget;
import mycelium.world_copy.v1.fixtures.MemoryTarget;
import mycelium.world_copy.v1.fixtures.NoOpTarget;
import mycelium.world_copy.v1.fixtures.OutOfRegionTarget;
import mycelium.world_copy.v1.fixtures.OverrunExecutor;
import mycelium.world_copy.v1.fixtures.PrematureReceiptExecutor;
import mycelium.world_copy.v1.model.WorldCopy;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("world.copy@1",
                () -> new WorldCopyContractTests(WorldCopy::execute, MemoryTarget::new).run(),
                List.of(
                        new ContractRun.Broken("NoOpTarget",
                                () -> new WorldCopyContractTests(WorldCopy::execute, NoOpTarget::new).run(),
                                "T1:target-equals-source"),
                        new ContractRun.Broken("LossyTarget",
                                () -> new WorldCopyContractTests(WorldCopy::execute, LossyTarget::new).run(),
                                "T3:block-entity-preserved"),
                        new ContractRun.Broken("LeakyTarget",
                                () -> new WorldCopyContractTests(WorldCopy::execute, LeakyTarget::new).run(),
                                "T2:sanitized-cleared"),
                        new ContractRun.Broken("OutOfRegionTarget",
                                () -> new WorldCopyContractTests(WorldCopy::execute, OutOfRegionTarget::new).run(),
                                "T4:no-write-outside-region"),
                        new ContractRun.Broken("OverrunExecutor",
                                () -> new WorldCopyContractTests(OverrunExecutor::execute, MemoryTarget::new).run(),
                                "T5:budget-rejected"),
                        new ContractRun.Broken("PrematureReceiptExecutor",
                                () -> new WorldCopyContractTests(PrematureReceiptExecutor::execute, MemoryTarget::new).run(),
                                "T6:no-feedback-after-abort")
                ));
        System.exit(exit);
    }
}
