package mycelium.random_seeded.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.random_seeded.v1.fixtures.JdkRandomSource;
import mycelium.random_seeded.v1.fixtures.LenientSource;
import mycelium.random_seeded.v1.fixtures.ModuloBiasedSource;
import mycelium.random_seeded.v1.fixtures.SharedStateSource;
import mycelium.random_seeded.v1.model.SeededRandom;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("random.seeded@1",
                () -> new RandomSeededContractTests(SeededRandom::new).run(),
                List.of(
                        new ContractRun.Broken("JdkRandomSource",
                                () -> new RandomSeededContractTests(JdkRandomSource::new).run(), "T1:splitmix-identical"),
                        new ContractRun.Broken("SharedStateSource",
                                () -> new RandomSeededContractTests(SharedStateSource::new).run(), "T2:same-seed-same-stream"),
                        new ContractRun.Broken("ModuloBiasedSource",
                                () -> new RandomSeededContractTests(ModuloBiasedSource::new).run(), "T4:no-modulo-bias"),
                        new ContractRun.Broken("LenientSource",
                                () -> new RandomSeededContractTests(LenientSource::new).run(), "T5:zero-bound")
                ));
        System.exit(exit);
    }
}
