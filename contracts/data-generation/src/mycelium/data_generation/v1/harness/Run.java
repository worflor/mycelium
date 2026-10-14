package mycelium.data_generation.v1.harness;

import mycelium.data_generation.v1.fixtures.ConstantNumberGenerations;
import mycelium.data_generation.v1.fixtures.ContentBlindHashGenerations;
import mycelium.data_generation.v1.fixtures.DanglingTolerantGenerations;
import mycelium.data_generation.v1.fixtures.DisturbingGenerations;
import mycelium.data_generation.v1.fixtures.EscapingGenerations;
import mycelium.data_generation.v1.fixtures.FailOpenGenerations;
import mycelium.data_generation.v1.fixtures.FirstFindingGenerations;
import mycelium.data_generation.v1.fixtures.InterpreterBlindHashGenerations;
import mycelium.data_generation.v1.fixtures.MapOrderHashGenerations;
import mycelium.data_generation.v1.fixtures.MixedGenerations;
import mycelium.data_generation.v1.fixtures.NoOpGenerations;
import mycelium.data_generation.v1.fixtures.PartOrderHashGenerations;
import mycelium.data_generation.v1.fixtures.PiecewiseGenerations;
import mycelium.data_generation.v1.fixtures.ShallowDropGenerations;
import mycelium.data_generation.v1.fixtures.TrustingGenerations;
import mycelium.data_generation.v1.fixtures.Utf16OrderHashGenerations;
import mycelium.data_generation.v1.model.StandardGenerations;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("data.generation@1",
                () -> new DataGenerationContractTests(StandardGenerations::new).run(),
                List.of(
                        new ContractRun.Broken("PiecewiseGenerations",
                                () -> new DataGenerationContractTests(PiecewiseGenerations::new).run(), "G1:never-partial"),
                        new ContractRun.Broken("MixedGenerations",
                                () -> new DataGenerationContractTests(MixedGenerations::new).run(), "G1:never-mixed"),
                        new ContractRun.Broken("FailOpenGenerations",
                                () -> new DataGenerationContractTests(FailOpenGenerations::new).run(), "G1:refused-is-kept"),
                        new ContractRun.Broken("DisturbingGenerations",
                                () -> new DataGenerationContractTests(DisturbingGenerations::new).run(), "G1:refused-keeps-current"),
                        new ContractRun.Broken("DanglingTolerantGenerations",
                                () -> new DataGenerationContractTests(DanglingTolerantGenerations::new).run(), "G2:no-dangling"),
                        new ContractRun.Broken("ShallowDropGenerations",
                                () -> new DataGenerationContractTests(ShallowDropGenerations::new).run(), "G2:optional-dropped-transitively"),
                        new ContractRun.Broken("PartOrderHashGenerations",
                                () -> new DataGenerationContractTests(PartOrderHashGenerations::new).run(), "G3:order-independent"),
                        new ContractRun.Broken("MapOrderHashGenerations",
                                () -> new DataGenerationContractTests(MapOrderHashGenerations::new).run(), "G3:order-independent"),
                        new ContractRun.Broken("Utf16OrderHashGenerations",
                                () -> new DataGenerationContractTests(Utf16OrderHashGenerations::new).run(), "G3:golden"),
                        new ContractRun.Broken("ContentBlindHashGenerations",
                                () -> new DataGenerationContractTests(ContentBlindHashGenerations::new).run(), "G3:content-sensitive"),
                        new ContractRun.Broken("InterpreterBlindHashGenerations",
                                () -> new DataGenerationContractTests(InterpreterBlindHashGenerations::new).run(), "G3:interpreter-sensitive"),
                        new ContractRun.Broken("ConstantNumberGenerations",
                                () -> new DataGenerationContractTests(ConstantNumberGenerations::new).run(), "G4:monotonic"),
                        new ContractRun.Broken("FirstFindingGenerations",
                                () -> new DataGenerationContractTests(FirstFindingGenerations::new).run(), "G6:all-findings"),
                        new ContractRun.Broken("TrustingGenerations",
                                () -> new DataGenerationContractTests(TrustingGenerations::new).run(), "G7:accounted"),
                        new ContractRun.Broken("EscapingGenerations",
                                () -> new DataGenerationContractTests(EscapingGenerations::new).run(), "G8:never-throws"),
                        new ContractRun.Broken("NoOpGenerations",
                                () -> new DataGenerationContractTests(NoOpGenerations::new).run(), "P1:consistent-published")
                ));
        System.exit(exit);
    }
}
