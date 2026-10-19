package mycelium.world_replay.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.world_replay.v1.fixtures.NoOpReplayCursor;
import mycelium.world_replay.v1.fixtures.ReplayCursorVariants;
import mycelium.world_replay.v1.fixtures.SharedPositionReplaySource;
import mycelium.world_replay.v1.model.ReplayProgram;
import mycelium.world_replay.v1.model.ReplayTraversal;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("world.replay@1",
                ReplayCursorContractTests::reference,
                List.of(
                        new ContractRun.Broken("ReorderedCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::reordered).run(),
                                "R1:ordered-visits"),
                        new ContractRun.Broken("NoOpReplayCursor",
                                () -> new ReplayCursorContractTests(NoOpReplayCursor::new).run(),
                                "R1:ordered-visits"),
                        new ContractRun.Broken("BudgetIgnoringCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::budgetIgnored).run(),
                                "R2:work-within-budget"),
                        new ContractRun.Broken("PerCellSkipCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::perCellSkip).run(),
                                "R3:whole-run-skip-costs-one"),
                        new ContractRun.Broken("AdvanceOnFailureCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::advancesOnFailure).run(),
                                "R4:failed-visit-is-retried"),
                        new ContractRun.Broken("TrustsMalformedDataCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::trustsMalformedData).run(),
                                "R5:malformed-run-rejected"),
                        new ContractRun.Broken("AliasingCursor",
                                () -> new ReplayCursorContractTests(ReplayCursorVariants::aliasesInput).run(),
                                "R6:input-is-copied"),
                        new ContractRun.Broken("SharedPositionReplaySource", () -> {
                            var check = new mycelium.harness.Check();
                            ReplayTraversal shared = new ReplayProgram(new int[]{1, 2, 0}, 4, 1).cursor();
                            ReplayCursorContractTests.programSourceConformance(check, new SharedPositionReplaySource(shared));
                            return check;
                        }, "R7:independent-cursors")
                ));
        System.exit(exit);
    }
}
