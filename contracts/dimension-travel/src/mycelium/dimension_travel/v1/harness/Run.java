package mycelium.dimension_travel.v1.harness;

import mycelium.dimension_travel.v1.fixtures.InventoryDropPort;
import mycelium.dimension_travel.v1.fixtures.MemoryTravelPort;
import mycelium.dimension_travel.v1.fixtures.NoOpPort;
import mycelium.dimension_travel.v1.fixtures.OffByOnePort;
import mycelium.dimension_travel.v1.fixtures.PrematureTravelReceiptExecutor;
import mycelium.dimension_travel.v1.fixtures.SwallowIndeterminateExecutor;
import mycelium.dimension_travel.v1.model.Travel;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("dimension.travel@1",
                () -> new TravelContractTests(Travel::execute, MemoryTravelPort::new).run(),
                List.of(
                        new ContractRun.Broken("PrematureTravelReceiptExecutor",
                                () -> new TravelContractTests(PrematureTravelReceiptExecutor::execute,
                                        MemoryTravelPort::new).run(),
                                "T10:target-unloaded-rejected-before-tick"),
                        new ContractRun.Broken("InventoryDropPort",
                                () -> new TravelContractTests(Travel::execute, InventoryDropPort::new).run(),
                                "T2:inventory-unchanged"),
                        new ContractRun.Broken("OffByOnePort",
                                () -> new TravelContractTests(Travel::execute, OffByOnePort::new).run(),
                                "T3:position-exact"),
                        new ContractRun.Broken("SwallowIndeterminateExecutor",
                                () -> new TravelContractTests(SwallowIndeterminateExecutor::execute,
                                        MemoryTravelPort::new).run(),
                                "T9:indeterminate-on-failure"),
                        new ContractRun.Broken("NoOpPort",
                                () -> new TravelContractTests(Travel::execute, NoOpPort::new).run(),
                                "T1:outcome-committed")
                ));
        System.exit(exit);
    }
}
