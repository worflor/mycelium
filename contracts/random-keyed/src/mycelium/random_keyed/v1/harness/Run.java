package mycelium.random_keyed.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.random_keyed.v1.fixtures.KeyedVariants;
import mycelium.random_keyed.v1.fixtures.NoOpKeyedRandom;
import mycelium.random_keyed.v1.model.KeyedRandom;
import mycelium.random_keyed.v1.model.ReferenceKeyedRandom;

import java.util.List;
import java.util.function.Supplier;

public final class Run {
    private Run() {
    }

    private static ContractRun.Broken broken(String name, Supplier<KeyedRandom> impl, String label) {
        return new ContractRun.Broken(name, () -> new RandomKeyedContractTests(impl.get()).run(), label);
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("random.keyed@1",
                () -> new RandomKeyedContractTests(new ReferenceKeyedRandom()).run(),
                List.of(
                        broken("UnkeyedDraw", KeyedVariants::unkeyedDraw, "K4:not-unkeyed"),
                        broken("ShortKeyDraw", KeyedVariants::shortKeyDraw, "K4:key-sensitive"),
                        broken("KeyXorDraw", KeyedVariants::keyXorDraw, "K4:one-draw-forecasts-no-other"),
                        broken("LenientKeyDraw", KeyedVariants::lenientKeyDraw, "K4:key-length"),
                        broken("ConcatenatingMessage", KeyedVariants::concatenatingMessage, "K3:injective"),
                        broken("UntaggedMessage", KeyedVariants::untaggedMessage, "K3:injective"),
                        broken("DomainBlindMessage", KeyedVariants::domainBlindMessage, "K3:domains-differ"),
                        broken("LittleEndianMessage", KeyedVariants::littleEndianMessage, "K3:encoding-exact"),
                        broken("LossyTextMessage", KeyedVariants::lossyTextMessage, "K3:refuses-ambiguous"),
                        broken("FloatUnit", KeyedVariants::floatUnit, "K5:edges-exact"),
                        broken("LowBitsUnit", KeyedVariants::lowBitsUnit, "K5:high-bits"),
                        broken("SignedShiftUnit", KeyedVariants::signedShiftUnit, "K5:range"),
                        broken("LenientUnit", KeyedVariants::lenientUnit, "K5:refuses"),
                        broken("StatefulDraw", KeyedVariants::statefulDraw, "K7:deterministic"),
                        broken("NoOpKeyedRandom", NoOpKeyedRandom::new, "K1:sha256-vectors")
                ));
        System.exit(exit);
    }
}
