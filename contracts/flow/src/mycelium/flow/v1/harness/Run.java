package mycelium.flow.v1.harness;

import mycelium.flow.v1.fixtures.IdentityMergerBackend;
import mycelium.flow.v1.fixtures.MemoryBackend;
import mycelium.flow.v1.fixtures.NoOpBackend;
import mycelium.flow.v1.fixtures.PrematureFinalizerExecutor;
import mycelium.flow.v1.fixtures.UndercountBackend;
import mycelium.flow.v1.fixtures.VoidingPartialBackend;
import mycelium.flow.v1.model.FlowBackend;
import mycelium.flow.v1.model.Transfer;
import mycelium.flow.v1.model.TransferExecutor;

import java.util.List;
import java.util.function.LongFunction;

public final class Run {
    private record Broken(String name, TransferExecutor executor,
                          LongFunction<FlowBackend> source, LongFunction<FlowBackend> destination,
                          String intendedLabel) {
    }

    private Run() {
    }

    public static void main(String[] args) {
        System.out.println("Mycelium - inventory.transfer@1 contract harness");
        System.out.println();

        FlowContractTests reference = new FlowContractTests(Transfer::execute, MemoryBackend::new, MemoryBackend::new);
        Check referenceCheck = reference.run();
        System.out.println("Reference (MemoryBackend + Transfer.execute): "
                + (referenceCheck.clean() ? "CLEAN" : "FAILURES"));
        for (String failure : referenceCheck.failures()) {
            System.out.println("  - " + failure);
        }

        List<Broken> broken = List.of(
                new Broken("NoOpBackend", Transfer::execute, NoOpBackend::new, MemoryBackend::new,
                        "T1:outcome-committed"),
                new Broken("UndercountBackend", Transfer::execute, UndercountBackend::new, MemoryBackend::new,
                        "T1:committed-7"),
                new Broken("VoidingPartialBackend", Transfer::execute, MemoryBackend::new, VoidingPartialBackend::new,
                        "T1:destination-7"),
                new Broken("IdentityMergerBackend", Transfer::execute, MemoryBackend::new, IdentityMergerBackend::new,
                        "T8:transferred-quantity"),
                new Broken("PrematureFinalizerExecutor", PrematureFinalizerExecutor::execute, MemoryBackend::new,
                        MemoryBackend::new, "T6:no-feedback-after-abort")
        );

        int missed = 0;
        System.out.println();
        System.out.println("Rejection matrix (each broken implementation must be caught):");
        for (Broken entry : broken) {
            FlowContractTests tests = new FlowContractTests(entry.executor(), entry.source(), entry.destination());
            Check check = tests.run();
            boolean caught = check.failures().contains(entry.intendedLabel());
            if (!caught) {
                missed++;
            }
            System.out.println("  " + entry.name() + ": " + (caught ? "CAUGHT" : "MISSED")
                    + " (intended: " + entry.intendedLabel() + ")");
            for (String failure : check.failures()) {
                System.out.println("      - " + failure);
            }
        }

        int exit = (referenceCheck.clean() ? 0 : referenceCheck.failures().size()) + missed;
        System.out.println();
        System.out.println("Result: " + (exit == 0 ? "PASS" : "FAIL")
                + " (reference failures: " + referenceCheck.failures().size()
                + ", missed rejections: " + missed + ")");
        System.exit(exit);
    }
}
