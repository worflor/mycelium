package mycelium.state_lifecycle.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.state_lifecycle.v1.fixtures.AnyoneOnlineHolds;
import mycelium.state_lifecycle.v1.fixtures.CloneEndsSource;
import mycelium.state_lifecycle.v1.fixtures.CloningExclusive;
import mycelium.state_lifecycle.v1.fixtures.DefaultPolicy;
import mycelium.state_lifecycle.v1.fixtures.FakeHost;
import mycelium.state_lifecycle.v1.fixtures.ForgetfulEngine;
import mycelium.state_lifecycle.v1.fixtures.ForgetfulStorage;
import mycelium.state_lifecycle.v1.fixtures.ForgetsMigration;
import mycelium.state_lifecycle.v1.fixtures.GlobalIncarnationCounter;
import mycelium.state_lifecycle.v1.fixtures.HalfCrossing;
import mycelium.state_lifecycle.v1.fixtures.IdOnlyResolver;
import mycelium.state_lifecycle.v1.fixtures.IgnoresDependencies;
import mycelium.state_lifecycle.v1.fixtures.LaxCompiler;
import mycelium.state_lifecycle.v1.fixtures.LazyMaterialize;
import mycelium.state_lifecycle.v1.fixtures.MaterializingPeek;
import mycelium.state_lifecycle.v1.fixtures.MaterializingRead;
import mycelium.state_lifecycle.v1.fixtures.MemoryHostStore;
import mycelium.state_lifecycle.v1.fixtures.MovingCopy;
import mycelium.state_lifecycle.v1.fixtures.NoOpEngine;
import mycelium.state_lifecycle.v1.fixtures.OverStrictCompiler;
import mycelium.state_lifecycle.v1.fixtures.PeekReadsDefault;
import mycelium.state_lifecycle.v1.fixtures.PreIncrementedCounter;
import mycelium.state_lifecycle.v1.fixtures.ReadIsPeek;
import mycelium.state_lifecycle.v1.fixtures.RefusesEveryHolder;
import mycelium.state_lifecycle.v1.fixtures.RetractIsNoop;
import mycelium.state_lifecycle.v1.fixtures.ReusedIncarnation;
import mycelium.state_lifecycle.v1.fixtures.RotatingEngine;
import mycelium.state_lifecycle.v1.fixtures.SessionLostOnRespawn;
import mycelium.state_lifecycle.v1.fixtures.SkipsNoOpEffects;
import mycelium.state_lifecycle.v1.fixtures.StrandedExclusive;
import mycelium.state_lifecycle.v1.fixtures.StringOnlyStorage;
import mycelium.state_lifecycle.v1.fixtures.SwallowedDecodeFailure;
import mycelium.state_lifecycle.v1.fixtures.SwallowsFailures;
import mycelium.state_lifecycle.v1.fixtures.SwallowsStaleHosts;
import mycelium.state_lifecycle.v1.fixtures.TerseDiagnostics;
import mycelium.state_lifecycle.v1.fixtures.TombstonedResolver;
import mycelium.state_lifecycle.v1.fixtures.UncheckedExclusive;
import mycelium.state_lifecycle.v1.fixtures.UndoOnlyOnSuccess;
import mycelium.state_lifecycle.v1.fixtures.UnloggedMaterialize;
import mycelium.state_lifecycle.v1.fixtures.UnloggedWrite;
import mycelium.state_lifecycle.v1.fixtures.WriteThroughHostId;
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
                new ContractRun.Broken("SkipsNoOpEffects",
                        () -> new StateLifecycleContractTests(new Rig(SkipsNoOpEffects::new, store, compiler)).run(),
                        "L1:plan-covers-scope"),
                new ContractRun.Broken("SwallowsStaleHosts",
                        () -> new StateLifecycleContractTests(new Rig(SwallowsStaleHosts::new, store, compiler)).run(),
                        "L2:stale-plan-refused"),
                new ContractRun.Broken("StrandedExclusive",
                        () -> new StateLifecycleContractTests(new Rig(StrandedExclusive::new, store, compiler)).run(),
                        "L3:clone-takes-the-token"),
                new ContractRun.Broken("MovingCopy",
                        () -> new StateLifecycleContractTests(new Rig(MovingCopy::new, store, compiler)).run(),
                        "L3:copy-is-on-both"),
                new ContractRun.Broken("CloneEndsSource",
                        () -> new StateLifecycleContractTests(new Rig(CloneEndsSource::new, store, compiler)).run(),
                        "L3:fixture-reads"),
                new ContractRun.Broken("SessionLostOnRespawn",
                        () -> new StateLifecycleContractTests(new Rig(SessionLostOnRespawn::new, store, compiler)).run(),
                        "L4:carried-session-value"),
                new ContractRun.Broken("SwallowedDecodeFailure",
                        () -> new StateLifecycleContractTests(new Rig(SwallowedDecodeFailure::new, store, compiler)).run(),
                        "L6:undecodable-load-refused"),
                new ContractRun.Broken("IgnoresDependencies",
                        () -> new StateLifecycleContractTests(new Rig(IgnoresDependencies::new, store, compiler)).run(),
                        "L7:dependency-then-id-order"),
                new ContractRun.Broken("ForgetsMigration",
                        () -> new StateLifecycleContractTests(new Rig(ForgetsMigration::new, store, compiler)).run(),
                        "L8:committed-crossing-applies"),
                new ContractRun.Broken("UndoOnlyOnSuccess",
                        () -> new StateLifecycleContractTests(new Rig(UndoOnlyOnSuccess::new, store, compiler)).run(),
                        "L8:failed-crossing-restores"),
                new ContractRun.Broken("SwallowsFailures",
                        () -> new StateLifecycleContractTests(new Rig(SwallowsFailures::new, store, compiler)).run(),
                        "L8:failure-reaches-the-caller"),
                new ContractRun.Broken("PreIncrementedCounter",
                        () -> new StateLifecycleContractTests(new Rig(engine, PreIncrementedCounter::new, compiler)).run(),
                        "L1:first-incarnation"),
                new ContractRun.Broken("ReusedIncarnation",
                        () -> new StateLifecycleContractTests(new Rig(engine, ReusedIncarnation::new, compiler)).run(),
                        "L2:incarnation-advances"),
                new ContractRun.Broken("TombstonedResolver",
                        () -> new StateLifecycleContractTests(new Rig(engine, TombstonedResolver::new, compiler)).run(),
                        "L2:live-ref-resolves"),
                new ContractRun.Broken("WriteThroughHostId",
                        () -> new StateLifecycleContractTests(new Rig(engine, WriteThroughHostId::new, compiler)).run(),
                        "L2:stale-access-gone"),
                new ContractRun.Broken("AnyoneOnlineHolds",
                        () -> new StateLifecycleContractTests(new Rig(engine, AnyoneOnlineHolds::new, compiler)).run(),
                        "L3:first-holder-created"),
                new ContractRun.Broken("UncheckedExclusive",
                        () -> new StateLifecycleContractTests(new Rig(engine, UncheckedExclusive::new, compiler)).run(),
                        "L3:second-holder-refused"),
                new ContractRun.Broken("GlobalIncarnationCounter",
                        () -> new StateLifecycleContractTests(new Rig(engine, GlobalIncarnationCounter::new, compiler)).run(),
                        "L3:fork-target-starts-at-first-incarnation"),
                new ContractRun.Broken("RefusesEveryHolder",
                        () -> new StateLifecycleContractTests(new Rig(engine, RefusesEveryHolder::new, compiler)).run(),
                        "L3:token-exercised"),
                new ContractRun.Broken("LazyMaterialize",
                        () -> new StateLifecycleContractTests(new Rig(engine, LazyMaterialize::new, compiler)).run(),
                        "L5:materialize-creates"),
                new ContractRun.Broken("PeekReadsDefault",
                        () -> new StateLifecycleContractTests(new Rig(engine, PeekReadsDefault::new, compiler)).run(),
                        "L5:peek-absent"),
                new ContractRun.Broken("ReadIsPeek",
                        () -> new StateLifecycleContractTests(new Rig(engine, ReadIsPeek::new, compiler)).run(),
                        "L5:read-gives-initial"),
                new ContractRun.Broken("MaterializingRead",
                        () -> new StateLifecycleContractTests(new Rig(engine, MaterializingRead::new, compiler)).run(),
                        "L5:read-pure"),
                new ContractRun.Broken("UnloggedWrite",
                        () -> new StateLifecycleContractTests(new Rig(engine, UnloggedWrite::new, compiler)).run(),
                        "L5:write-undone-on-abort"),
                new ContractRun.Broken("StringOnlyStorage",
                        () -> new StateLifecycleContractTests(new Rig(engine, StringOnlyStorage::new, compiler)).run(),
                        "L6:component-codec-round-trip"),
                new ContractRun.Broken("UnloggedMaterialize",
                        () -> new StateLifecycleContractTests(new Rig(engine, UnloggedMaterialize::new, compiler)).run(),
                        "L8:aborted-write-restores"),
                new ContractRun.Broken("RetractIsNoop",
                        () -> new StateLifecycleContractTests(new Rig(engine, RetractIsNoop::new, compiler)).run(),
                        "L8:undecodable-load-aborts"),
                new ContractRun.Broken("AcceptsUnknownTransition",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.IGNORES_UNKNOWN_TRANSITION))).run(),
                        "L1:unknown-transition-rejected"),
                new ContractRun.Broken("AcceptsOutOfScopePolicy",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.IGNORES_OUT_OF_SCOPE_POLICY))).run(),
                        "L1:out-of-scope-rejected"),
                new ContractRun.Broken("AcceptsCopyAwayFromForks",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.COPY_ANYWHERE))).run(),
                        "L1:copy-only-on-forks"),
                new ContractRun.Broken("AcceptsRegenerateAwayFromForks",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.REGENERATE_ANYWHERE))).run(),
                        "L1:regenerate-only-on-forks"),
                new ContractRun.Broken("AcceptsExclusiveAwayFromForks",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.EXCLUSIVE_ANYWHERE))).run(),
                        "L1:exclusive-only-on-forks"),
                new ContractRun.Broken("AcceptsSessionKeptPastEnd",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.SESSION_KEPT_PAST_END))).run(),
                        "L1:session-dropped-at-end"),
                new ContractRun.Broken("AcceptsDurableWithoutCodec",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.CODECLESS_DURABLE))).run(),
                        "L1:durable-needs-codec"),
                new ContractRun.Broken("AcceptsDependencyCycle",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.BLIND_TO_CYCLES))).run(),
                        "L1:cycle-rejected"),
                new ContractRun.Broken("AcceptsUnknownDependency",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.BLIND_TO_UNKNOWN_DEPENDENCY))).run(),
                        "L1:unknown-dependency"),
                new ContractRun.Broken("AcceptsCrossScopeDependency",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.BLIND_TO_CROSS_SCOPE_DEPENDENCY))).run(),
                        "L1:cross-scope-dependency"),
                new ContractRun.Broken("AcceptsDuplicateSlot",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.SILENT_DUPLICATE))).run(),
                        "L1:duplicate-slot-rejected"),
                new ContractRun.Broken("AcceptsExclusiveThatCreatesValues",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new LaxCompiler(LaxCompiler.Lapse.EXCLUSIVE_MAY_CREATE))).run(),
                        "L1:exclusive-creates-value"),
                new ContractRun.Broken("RefusesSoundDeclarations",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new OverStrictCompiler())).run(),
                        "L1:complete-accepted"),
                new ContractRun.Broken("TerseDiagnostics",
                        () -> new StateLifecycleContractTests(new Rig(engine, store, new TerseDiagnostics())).run(),
                        "L1:diagnostic-names-slot-and-transition"),
                new ContractRun.Broken("NoOpEngine",
                        () -> new StateLifecycleContractTests(new Rig(NoOpEngine::new, store, compiler)).run(),
                        "L4:incarnation-ends")
        );

        int exit = ContractRun.run("state.lifecycle@1",
                () -> new StateLifecycleContractTests(new Rig(engine, store, compiler)).run(), matrix);
        System.exit(exit);
    }
}
