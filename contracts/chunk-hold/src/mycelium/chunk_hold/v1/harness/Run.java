package mycelium.chunk_hold.v1.harness;

import mycelium.chunk_hold.v1.fixtures.EngineDirectKeeper;
import mycelium.chunk_hold.v1.fixtures.LedgerVariants;
import mycelium.chunk_hold.v1.fixtures.NoOpKeeper;
import mycelium.chunk_hold.v1.model.LedgerKeeper;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("chunk.hold@1",
                () -> new ChunkHoldContractTests(LedgerKeeper::new).run(),
                List.of(
                        new ContractRun.Broken("EngineDirectKeeper",
                                () -> new ChunkHoldContractTests(EngineDirectKeeper::new).run(), "H2:release-drops-only-your-own"),
                        new ContractRun.Broken("NeverDropsKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::neverDrops).run(), "H1:ticketed-exactly-while-held"),
                        new ContractRun.Broken("StrangerDropsKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::strangerDrops).run(), "H3:stranger-drops-nothing"),
                        new ContractRun.Broken("EternalLeaseKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::eternalLeases).run(), "H5:lease-lapses-on-time"),
                        new ContractRun.Broken("ShortLeaseKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::shortLeases).run(), "H5:lease-lapses-on-time"),
                        new ContractRun.Broken("RenewsOwnersOnlyKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::renewsOwnersOnly).run(), "H6:renew-takes-every-held"),
                        new ContractRun.Broken("NoOpKeeper",
                                () -> new ChunkHoldContractTests(NoOpKeeper::new).run(), "H1:ticketed-exactly-while-held"),
                        new ContractRun.Broken("ReleaseAllOverreachKeeper",
                                () -> new ChunkHoldContractTests(LedgerVariants::releaseAllOverreaches).run(), "H4:release-all-exactly")
                ));
        System.exit(exit);
    }
}
