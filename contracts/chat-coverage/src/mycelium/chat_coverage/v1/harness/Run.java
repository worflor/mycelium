package mycelium.chat_coverage.v1.harness;

import mycelium.chat_coverage.v1.fixtures.BrokenCoverages;
import mycelium.chat_coverage.v1.model.CoverageLedger;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("chat.coverage@1",
                () -> new ChatCoverageContractTests(CoverageLedger::new).run(),
                List.of(
                        new ContractRun.Broken("NoOpCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::noOp).run(),
                                "P1:durable-next-event-is-recorded"),
                        new ContractRun.Broken("IncompleteEmptyCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::incompleteEmpty).run(),
                                "L1:established-empty-run-is-complete"),
                        new ContractRun.Broken("CompleteUnknownCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::completeUnknown).run(),
                                "L2:unknown-start-is-incomplete"),
                        new ContractRun.Broken("GapAcceptingCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::gapAccepting).run(),
                                "L3:only-next-sequence-advances"),
                        new ContractRun.Broken("SilentLossCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::silentLoss).run(),
                                "L4:loss-is-sticky"),
                        new ContractRun.Broken("LaunderingRestoreCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::launderingRestore).run(),
                                "L5:restore-never-heals"),
                        new ContractRun.Broken("ReplacingCauseCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::replacingCause).run(),
                                "L6:first-loss-cause-is-stable"),
                        new ContractRun.Broken("ResettingTransitionCoverage",
                                () -> new ChatCoverageContractTests(BrokenCoverages::resettingTransition).run(),
                                "L7:snapshot-is-immutable")
                ));
        System.exit(exit);
    }
}
