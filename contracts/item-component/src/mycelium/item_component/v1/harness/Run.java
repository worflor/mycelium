package mycelium.item_component.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.item_component.v1.fixtures.*;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("item.component@1",
                () -> new ItemComponentContractTests(ReferenceTranslator::create).run(),
                List.of(
                        new ContractRun.Broken("NoOpTranslator",
                                () -> new ItemComponentContractTests(NoOpTranslator::new).run(),
                                "T1:round-trip-isomorphism"),
                        new ContractRun.Broken("DefaultWritingTranslator",
                                () -> new ItemComponentContractTests(DefaultWritingTranslator::new).run(),
                                "T2:default-elision"),
                        new ContractRun.Broken("RootPollutingTranslator",
                                () -> new ItemComponentContractTests(RootPollutingTranslator::new).run(),
                                "T3:namespace-fencing"),
                        new ContractRun.Broken("LossyUnknownTagTranslator",
                                () -> new ItemComponentContractTests(LossyUnknownTagTranslator::new).run(),
                                "T4:foreign-tag-preservation"),
                        new ContractRun.Broken("CrashingParseTranslator",
                                () -> new ItemComponentContractTests(CrashingParseTranslator::new).run(),
                                "T5:corruption-rejected-safely")
                ));
        System.exit(exit);
    }
}
