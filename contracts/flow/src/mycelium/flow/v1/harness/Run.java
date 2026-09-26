package mycelium.flow.v1.harness;

import mycelium.flow.v1.fixtures.IdentityMergerBackend;
import mycelium.flow.v1.fixtures.MemoryBackend;
import mycelium.flow.v1.fixtures.NoOpBackend;
import mycelium.flow.v1.fixtures.NoRestoreExecutor;
import mycelium.flow.v1.fixtures.PrematureFinalizerExecutor;
import mycelium.flow.v1.fixtures.UncheckedExtractionExecutor;
import mycelium.flow.v1.fixtures.UndercountBackend;
import mycelium.flow.v1.fixtures.VoidingPartialBackend;
import mycelium.flow.v1.model.Transfer;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("inventory.transfer@1",
                () -> new FlowContractTests(Transfer::execute, MemoryBackend::new, MemoryBackend::new).run(),
                List.of(
                        new ContractRun.Broken("NoOpBackend",
                                () -> new FlowContractTests(Transfer::execute, NoOpBackend::new, MemoryBackend::new).run(),
                                "T1:outcome-committed"),
                        new ContractRun.Broken("UndercountBackend",
                                () -> new FlowContractTests(Transfer::execute, UndercountBackend::new, MemoryBackend::new).run(),
                                "T1:committed-7"),
                        new ContractRun.Broken("VoidingPartialBackend",
                                () -> new FlowContractTests(Transfer::execute, MemoryBackend::new, VoidingPartialBackend::new).run(),
                                "T1:destination-7"),
                        new ContractRun.Broken("IdentityMergerBackend",
                                () -> new FlowContractTests(Transfer::execute, MemoryBackend::new, IdentityMergerBackend::new).run(),
                                "T8:transferred-quantity"),
                        new ContractRun.Broken("PrematureFinalizerExecutor",
                                () -> new FlowContractTests(PrematureFinalizerExecutor::execute, MemoryBackend::new, MemoryBackend::new).run(),
                                "T6:no-feedback-after-abort"),
                        new ContractRun.Broken("NoRestoreExecutor",
                                () -> new FlowContractTests(NoRestoreExecutor::execute, MemoryBackend::new, MemoryBackend::new).run(),
                                "T11:source-restored"),
                        new ContractRun.Broken("UncheckedExtractionExecutor",
                                () -> new FlowContractTests(UncheckedExtractionExecutor::execute, MemoryBackend::new, MemoryBackend::new).run(),
                                "T13:extraction-failed")
                ));
        System.exit(exit);
    }
}
