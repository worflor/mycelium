package mycelium.block_presentation.v1.harness;

import mycelium.block_presentation.v1.fixtures.CapIgnoring;
import mycelium.block_presentation.v1.fixtures.HashOrderEmitter;
import mycelium.block_presentation.v1.fixtures.LeakyProjection;
import mycelium.block_presentation.v1.fixtures.ManifestOmitsTemplates;
import mycelium.block_presentation.v1.fixtures.ManifestOverreach;
import mycelium.block_presentation.v1.fixtures.NoOpMosaic;
import mycelium.block_presentation.v1.fixtures.ReversedTurn;
import mycelium.block_presentation.v1.fixtures.SwappedOrder;
import mycelium.block_presentation.v1.model.ReferenceMosaic;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("block.presentation@1",
                () -> new MosaicContractTests(new ReferenceMosaic()).run(),
                List.of(
                        new ContractRun.Broken("ReversedTurn",
                                () -> new MosaicContractTests(new ReversedTurn()).run(),
                                "M2:turn-carried-exactly"),
                        new ContractRun.Broken("SwappedOrder",
                                () -> new MosaicContractTests(new SwappedOrder()).run(),
                                "M2:declaration-order"),
                        new ContractRun.Broken("ManifestOmitsTemplates",
                                () -> new MosaicContractTests(new ManifestOmitsTemplates()).run(),
                                "M4:manifest-covers-every-drawn-model"),
                        new ContractRun.Broken("ManifestOverreach",
                                () -> new MosaicContractTests(new ManifestOverreach()).run(),
                                "M4:manifest-has-no-undrawn-model"),
                        new ContractRun.Broken("LeakyProjection",
                                () -> new MosaicContractTests(new LeakyProjection()).run(),
                                "M5:server-private-rejected"),
                        new ContractRun.Broken("HashOrderEmitter",
                                () -> new MosaicContractTests(new HashOrderEmitter()).run(),
                                "M6:canonical-json"),
                        new ContractRun.Broken("CapIgnoring",
                                () -> new MosaicContractTests(new CapIgnoring()).run(),
                                "M7:part-span-over-cap-rejected"),
                        new ContractRun.Broken("NoOpMosaic",
                                () -> new MosaicContractTests(new NoOpMosaic()).run(),
                                "M1:totality-agrees-with-evaluator")));
        System.exit(exit);
    }
}
