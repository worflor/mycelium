package mycelium.rule_effects.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.rule_effects.v1.fixtures.GlobalEffectPort;
import mycelium.rule_effects.v1.fixtures.MemoryEffectPort;
import mycelium.rule_effects.v1.fixtures.NoOpPort;
import mycelium.rule_effects.v1.fixtures.PrematureReceiptExecutor;
import mycelium.rule_effects.v1.fixtures.StackingEffectPort;
import mycelium.rule_effects.v1.fixtures.StickyEffectPort;
import mycelium.rule_effects.v1.model.RuleEffects;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("rule.effects@1",
                () -> new RuleEffectsContractTests(RuleEffects::apply, MemoryEffectPort::new).run(),
                List.of(
                        new ContractRun.Broken("StickyEffectPort",
                                () -> new RuleEffectsContractTests(RuleEffects::apply, StickyEffectPort::new).run(),
                                "T4:symmetry"),
                        new ContractRun.Broken("StackingEffectPort",
                                () -> new RuleEffectsContractTests(RuleEffects::apply, StackingEffectPort::new).run(),
                                "T3:no-stacking"),
                        new ContractRun.Broken("GlobalEffectPort",
                                () -> new RuleEffectsContractTests(RuleEffects::apply, GlobalEffectPort::new).run(),
                                "T1:scope-untouched"),
                        new ContractRun.Broken("NoOpPort",
                                () -> new RuleEffectsContractTests(RuleEffects::apply, NoOpPort::new).run(),
                                "T1:active-contains-ids"),
                        new ContractRun.Broken("PrematureReceiptExecutor",
                                () -> new RuleEffectsContractTests(PrematureReceiptExecutor::apply, MemoryEffectPort::new).run(),
                                "T6:no-feedback-after-abort")));
        System.exit(exit);
    }
}
