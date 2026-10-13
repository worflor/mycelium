package mycelium.item_presentation.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.item_presentation.v1.fixtures.DescendingOverrides;
import mycelium.item_presentation.v1.fixtures.EnumerationOrderCodes;
import mycelium.item_presentation.v1.fixtures.HashOrderEmitter;
import mycelium.item_presentation.v1.fixtures.InvertedContexts;
import mycelium.item_presentation.v1.fixtures.LeakyProjection;
import mycelium.item_presentation.v1.fixtures.NoOpCompiler;
import mycelium.item_presentation.v1.fixtures.OffByOneLegacy;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("item.presentation@1",
                () -> new SigilContractTests(new ReferenceSigil()).run(),
                List.of(
                        new ContractRun.Broken("EnumerationOrderCodes",
                                () -> new SigilContractTests(new EnumerationOrderCodes()).run(),
                                "S3:existing-codes-kept"),
                        new ContractRun.Broken("DescendingOverrides",
                                () -> new SigilContractTests(new DescendingOverrides()).run(),
                                "S4:overrides-strictly-ascending"),
                        new ContractRun.Broken("OffByOneLegacy",
                                () -> new SigilContractTests(new OffByOneLegacy()).run(),
                                "S2:legacy-draws-evaluated-model"),
                        new ContractRun.Broken("LeakyProjection",
                                () -> new SigilContractTests(new LeakyProjection()).run(),
                                "S5:server-private-rejected"),
                        new ContractRun.Broken("HashOrderEmitter",
                                () -> new SigilContractTests(new HashOrderEmitter()).run(),
                                "S6:canonical-json"),
                        new ContractRun.Broken("InvertedContexts",
                                () -> new SigilContractTests(new InvertedContexts()).run(),
                                "S8:definition-draws-context-rules"),
                        new ContractRun.Broken("NoOpCompiler",
                                () -> new SigilContractTests(new NoOpCompiler()).run(),
                                "S1:totality-agrees-with-evaluator")));
        System.exit(exit);
    }
}
