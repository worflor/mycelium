package mycelium.data_catalog.v1.harness;

import mycelium.data_catalog.v1.fixtures.AbortOnFirstBadCatalog;
import mycelium.data_catalog.v1.fixtures.IncrementalCatalog;
import mycelium.data_catalog.v1.fixtures.ListOrderWinsCatalog;
import mycelium.data_catalog.v1.fixtures.MergingCatalog;
import mycelium.data_catalog.v1.model.ReferenceCatalog;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("data.catalog@1",
                () -> new DataCatalogContractTests(ReferenceCatalog::new).run(),
                List.of(
                        new ContractRun.Broken("AbortOnFirstBadCatalog",
                                () -> new DataCatalogContractTests(AbortOnFirstBadCatalog::new).run(), "T2:reload-threw"),
                        new ContractRun.Broken("MergingCatalog",
                                () -> new DataCatalogContractTests(MergingCatalog::new).run(), "T6:removed-is-gone"),
                        new ContractRun.Broken("ListOrderWinsCatalog",
                                () -> new DataCatalogContractTests(ListOrderWinsCatalog::new).run(), "T3:later-pack-wins"),
                        new ContractRun.Broken("IncrementalCatalog",
                                () -> new DataCatalogContractTests(IncrementalCatalog::new).run(), "T4:old-whole-until-done")
                ));
        System.exit(exit);
    }
}
