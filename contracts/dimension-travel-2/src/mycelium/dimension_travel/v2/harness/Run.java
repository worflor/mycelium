package mycelium.dimension_travel.v2.harness;

import mycelium.dimension_travel.v2.fixtures.ArriveExecutors;
import mycelium.dimension_travel.v2.fixtures.EagerSettleExecutor;
import mycelium.dimension_travel.v2.fixtures.ForgetfulJournalPort;
import mycelium.dimension_travel.v2.fixtures.LateJournalExecutor;
import mycelium.dimension_travel.v2.fixtures.MemoryTravelPort;
import mycelium.dimension_travel.v2.fixtures.NeverStrikePort;
import mycelium.dimension_travel.v2.fixtures.NoOpJournalPort;
import mycelium.dimension_travel.v2.fixtures.OverwritingDepartExecutor;
import mycelium.dimension_travel.v2.fixtures.SilentRecoverExecutor;
import mycelium.dimension_travel.v2.model.Trip;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("dimension.travel@2",
                () -> new TripContractTests(Trip.REFERENCE, MemoryTravelPort::new).run(),
                List.of(
                        new ContractRun.Broken("LateJournalExecutor",
                                () -> new TripContractTests(new LateJournalExecutor(), MemoryTravelPort::new).run(),
                                "T1:journal-before-move"),
                        new ContractRun.Broken("NoOpJournalPort",
                                () -> new TripContractTests(Trip.REFERENCE, NoOpJournalPort::new).run(),
                                "T1:journal-written"),
                        new ContractRun.Broken("OverwritingDepartExecutor",
                                () -> new TripContractTests(new OverwritingDepartExecutor(), MemoryTravelPort::new).run(),
                                "T2:second-departure-rejected"),
                        new ContractRun.Broken("ForgetfulJournalPort",
                                () -> new TripContractTests(Trip.REFERENCE, ForgetfulJournalPort::new).run(),
                                "T3:journal-survives-crash"),
                        new ContractRun.Broken("NeverStrikePort",
                                () -> new TripContractTests(Trip.REFERENCE, NeverStrikePort::new).run(),
                                "T5:journal-struck-on-return"),
                        new ContractRun.Broken("SilentRecoverExecutor",
                                () -> new TripContractTests(new SilentRecoverExecutor(), MemoryTravelPort::new).run(),
                                "T6:settled-once"),
                        new ContractRun.Broken("EagerSettleExecutor",
                                () -> new TripContractTests(new EagerSettleExecutor(), MemoryTravelPort::new).run(),
                                "T7:no-settle-before-commit"),
                        new ContractRun.Broken("ArriveKeepsJournal",
                                () -> new TripContractTests(ArriveExecutors.keepsJournal(), MemoryTravelPort::new).run(),
                                "T12:arrive-strikes-the-trip"),
                        new ContractRun.Broken("ArriveMovesHome",
                                () -> new TripContractTests(ArriveExecutors.movesHome(), MemoryTravelPort::new).run(),
                                "T12:arrive-moves-no-one"),
                        new ContractRun.Broken("ArriveIgnoresAbort",
                                () -> new TripContractTests(ArriveExecutors.ignoresAbort(), MemoryTravelPort::new).run(),
                                "T12:arrive-is-undone-by-an-abort")
                ));
        System.exit(exit);
    }
}
