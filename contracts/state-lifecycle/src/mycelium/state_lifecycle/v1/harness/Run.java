package mycelium.state_lifecycle.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.state_lifecycle.v1.fixtures.CloningExclusive;
import mycelium.state_lifecycle.v1.fixtures.DefaultPolicy;
import mycelium.state_lifecycle.v1.fixtures.FakeHost;
import mycelium.state_lifecycle.v1.fixtures.ForgetfulEngine;
import mycelium.state_lifecycle.v1.fixtures.ForgetfulStorage;
import mycelium.state_lifecycle.v1.fixtures.HalfCrossing;
import mycelium.state_lifecycle.v1.fixtures.IdOnlyResolver;
import mycelium.state_lifecycle.v1.fixtures.MaterializingPeek;
import mycelium.state_lifecycle.v1.fixtures.MemoryHostStore;
import mycelium.state_lifecycle.v1.fixtures.NoOpEngine;
import mycelium.state_lifecycle.v1.fixtures.RotatingEngine;
import mycelium.state_lifecycle.v1.model.Crossings;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;
import mycelium.state_lifecycle.v1.model.StandardCompiler;
import mycelium.state_lifecycle.v1.model.StandardCrossings;

import java.util.List;
import java.util.function.Supplier;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        Supplier<Crossings> engine = () -> StandardCrossings.INSTANCE;
        Supplier<MemoryHostStore<FakeHost>> store = MemoryHostStore::new;
        RegistryCompiler compiler = StandardCompiler.INSTANCE;

        List<ContractRun.Broken> matrix = List.of(
                new ContractRun.Broken("ForgetfulEngine",
                        () -> new StateLifecycleContractTests(new Rig(ForgetfulEngine::new, store, compiler)).run(),
                        "L4:session-slots-empty"),
                new ContractRun.Broken("IdOnlyResolver",
                        () -> new StateLifecycleContractTests(new Rig(engine, IdOnlyResolver::new, compiler)).run(),
                        "L2:stale-ref-gone"),
                new ContractRun.Broken("CloningExclusive",
                        () -> new StateLifecycleContractTests(new Rig(CloningExclusive::new, store, compiler)).run(),
                        "L3:exclusive-at-most-one"),
                new ContractRun.Broken("MaterializingPeek",
                        () -> new StateLifecycleContractTests(new Rig(engine, MaterializingPeek::new, compiler)).run(),
                        "L5:peek-pure"),
                new ContractRun.Broken("DefaultPolicy",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new DefaultPolicy())).run(),
                        "L1:missing-policy-rejected"),
                new ContractRun.Broken("HalfCrossing",
                        () -> new StateLifecycleContractTests(new Rig(HalfCrossing::new, store, compiler)).run(),
                        "L8:aborted-crossing-restores"),
                new ContractRun.Broken("ForgetfulStorage",
                        () -> new StateLifecycleContractTests(new Rig(engine, ForgetfulStorage::new, compiler)).run(),
                        "L6:durable-round-trip"),
                new ContractRun.Broken("RotatingEngine",
                        () -> new StateLifecycleContractTests(new Rig(RotatingEngine::new, store, compiler)).run(),
                        "L7:same-input-same-plan"),
                new ContractRun.Broken("NoOpEngine",
                        () -> new StateLifecycleContractTests(new Rig(NoOpEngine::new, store, compiler)).run(),
                        "L4:incarnation-ends")
        );

        int exit = ContractRun.run("state.lifecycle@1",
                () -> new StateLifecycleContractTests(new Rig(engine, store, compiler)).run(), matrix);
        System.exit(exit);
    }
}
