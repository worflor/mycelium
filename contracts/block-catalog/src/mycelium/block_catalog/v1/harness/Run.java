package mycelium.block_catalog.v1.harness;

import mycelium.block_catalog.v1.fixtures.AcceptsBadDefinitionFactory;
import mycelium.block_catalog.v1.fixtures.AliasesInputsFactory;
import mycelium.block_catalog.v1.fixtures.IgnoresExplicitFactory;
import mycelium.block_catalog.v1.fixtures.MissingDefaultsFactory;
import mycelium.block_catalog.v1.fixtures.MutableOutputsFactory;
import mycelium.block_catalog.v1.fixtures.NoOpCatalogSource;
import mycelium.block_catalog.v1.model.BlockCatalog;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("block.catalog@1",
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
                                () -> new BlockCatalogContractTests(MutableOutputsFactory::new).run(), "C5:deep-immutable")
                ));
        System.exit(exit);
    }
}
