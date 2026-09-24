package mycelium.cue.v1.harness;

import mycelium.cue.v1.fixtures.DoubleCueScheduler;
import mycelium.cue.v1.fixtures.NoOpScheduler;
import mycelium.cue.v1.fixtures.PrematureCueScheduler;
import mycelium.cue.v1.fixtures.SilentFallbackScheduler;
import mycelium.cue.v1.model.ReferenceCueScheduler;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        List<ContractRun.Broken> matrix = List.of(
                new ContractRun.Broken("PrematureCueScheduler",
                        () -> new CueContractTests(PrematureCueScheduler::new).run(),
                        "T1:no-cue-before-commit"),
                new ContractRun.Broken("DoubleCueScheduler",
                        () -> new CueContractTests(DoubleCueScheduler::new).run(),
                        "T6:exactly-one-cue-per-receipt"),
                new ContractRun.Broken("SilentFallbackScheduler",
                        () -> new CueContractTests(SilentFallbackScheduler::new).run(),
                        "T3:unsupported"),
                new ContractRun.Broken("NoOpScheduler",
                        () -> new CueContractTests(NoOpScheduler::new).run(),
                        "T7:every-receipt-emitted-once")
        );

        int exit = ContractRun.run("presentation.particle-cue@1",
                () -> new CueContractTests(ReferenceCueScheduler::new).run(), matrix);
        System.exit(exit);
    }
}
