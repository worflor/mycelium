package mycelium.block_catalog.v2.harness;

import mycelium.block_catalog.v2.fixtures.AcceptsBadDefinitionFactory;
import mycelium.block_catalog.v2.fixtures.AliasesInputsFactory;
import mycelium.block_catalog.v2.fixtures.IgnoresExplicitFactory;
import mycelium.block_catalog.v2.fixtures.MissingDefaultsFactory;
import mycelium.block_catalog.v2.fixtures.MutableOutputsFactory;
import mycelium.block_catalog.v2.fixtures.NoOpCatalogSource;
import mycelium.block_catalog.v2.fixtures.SortsDomainsFactory;
import mycelium.block_catalog.v2.model.BlockCatalog;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("block.catalog@2",
                () -> new BlockCatalogContractTests(() -> BlockCatalog::new).run(),
                List.of(
                        new ContractRun.Broken("NoOpCatalogSource",
                                () -> new BlockCatalogContractTests(NoOpCatalogSource::new).run(), "C1:known-blocks"),
                        new ContractRun.Broken("AcceptsBadDefinitionFactory",
                                () -> new BlockCatalogContractTests(AcceptsBadDefinitionFactory::new).run(), "C4:rejects-malformed"),
                        new ContractRun.Broken("MissingDefaultsFactory",
                                () -> new BlockCatalogContractTests(MissingDefaultsFactory::new).run(), "C2:all-defaults"),
                        new ContractRun.Broken("IgnoresExplicitFactory",
                                () -> new BlockCatalogContractTests(IgnoresExplicitFactory::new).run(), "C3:explicit-overrides"),
                        new ContractRun.Broken("AliasesInputsFactory",
                                () -> new BlockCatalogContractTests(AliasesInputsFactory::new).run(), "C5:deep-immutable"),
                        new ContractRun.Broken("MutableOutputsFactory",
                                () -> new BlockCatalogContractTests(MutableOutputsFactory::new).run(), "C5:deep-immutable"),
                        new ContractRun.Broken("SortsDomainsFactory",
                                () -> new BlockCatalogContractTests(SortsDomainsFactory::new).run(), "C6:capture-order")
                ));
        System.exit(exit);
    }
}
