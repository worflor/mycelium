package mycelium.world_clock.v1.harness;

import mycelium.harness.ContractRun;
import mycelium.world_clock.v1.fixtures.ClockVariants;
import mycelium.world_clock.v1.fixtures.MemoryClockPort;
import mycelium.world_clock.v1.fixtures.NoOpClockModel;
import mycelium.world_clock.v1.model.ClockModel;
import mycelium.world_clock.v1.model.ClockPort;
import mycelium.world_clock.v1.model.ClockWorld;
import mycelium.world_clock.v1.model.ReferenceClockModel;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public final class Run {
    private Run() {
    }

    private static final BiFunction<ClockModel, ClockWorld, ClockPort> MEMORY = MemoryClockPort::new;

    private static ContractRun.Broken broken(String name, ClockModel model, String label) {
        return broken(name, model, MEMORY, label);
    }

    private static ContractRun.Broken broken(String name, ClockModel model, BiFunction<ClockModel, ClockWorld, ClockPort> ports,
                                             String label) {
        Supplier<mycelium.harness.Check> run = () -> new WorldClockContractTests(model, ports).run();
        return new ContractRun.Broken(name, run, label);
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("world.clock@1",
                () -> new WorldClockContractTests(new ReferenceClockModel(), MEMORY).run(),
                List.of(
                        broken("DroppedCarry", ClockVariants.droppedCarry(), "C1:fraction-carries"),
                        broken("RuleBlind", ClockVariants.ruleBlind(), "C1:standstill"),
                        broken("PauseBlind", ClockVariants.pauseBlind(), "C1:standstill"),
                        broken("DriftingCarry", ClockVariants.driftingCarry(), "C1:accumulates-n-times-rate"),
                        broken("PauseDropsFraction", ClockVariants.pauseDropsFraction(), "C1:sequence-tracks-exact"),
                        broken("BackwardsMarker", ClockVariants.backwardsMarker(), "C2:moves-forward-only"),
                        broken("AlwaysMoves", ClockVariants.alwaysMoves(), "C2:at-marker-not-moved"),
                        broken("StalePartial", ClockVariants.stalePartial(), "C2:lands-clean"),
                        broken("SilentAbsentMarker", ClockVariants.silentAbsentMarker(), "C2:no-such-marker"),
                        broken("AddDropsFraction", ClockVariants.addDropsFraction(), "C2:set-add-exact"),
                        broken("OneShotNeverBackwards", ClockVariants.oneShotNeverBackwards(), "C2:one-shot-goes-to-its-tick"),
                        broken("OffByOneRepetition", ClockVariants.offByOneRepetition(), "C3:repetition-count"),
                        broken("PeriodsFromOne", ClockVariants.periodsFromOne(), "C3:periods-recompose"),
                        broken("OneEdgePerGap", ClockVariants.oneEdgePerGap(), "C3:edges-each-once"),
                        broken("EdgesCountBackwards", ClockVariants.edgesCountBackwards(), "C3:dawn-is-the-day-edge"),
                        broken("OpenTimelineHasNoTicks", ClockVariants.openTimelineHasNoTicks(), "C3:non-periodic-timeline"),
                        broken("OccursOnlyInFirstPeriod", ClockVariants.occursOnlyInFirstPeriod(), "C3:occurs-at"),
                        broken("NextIncludesNow", ClockVariants.nextIncludesNow(), "C3:previous-next-occurrence"),
                        broken("EarlySleepSkip", ClockVariants.earlySleepSkip(), "C4:sleep-skip"),
                        broken("SleepIgnoresRule", ClockVariants.sleepIgnoresRule(), "C4:rule-off-no-skip"),
                        broken("LatePhase", ClockVariants.latePhase(), "C4:moon-phase"),
                        broken("FloatTotal", ClockVariants.floatTotal(), "C4:tick"),
                        broken("SleepWithoutMarkerSkipsToDawn", ClockVariants.sleepWithoutMarkerSkipsToDawn(),
                                "C4:no-wake-marker-no-skip"),
                        broken("SleepAtMarkerSkipsADay", ClockVariants.sleepAtMarkerSkipsADay(), "C4:sleep-at-the-marker"),
                        broken("PortSleepIgnoresRule", new ReferenceClockModel(), ClockVariants::portSleepIgnoresRule,
                                "C4:script-matches-old-game"),
                        broken("SleepsTheWrongClock", new ReferenceClockModel(), ClockVariants::sleepsTheWrongClock,
                                "C4:sleep-uses-default-clock"),
                        broken("MarkersFromOverworldOnly", new ReferenceClockModel(), ClockVariants::markersFromOverworldOnly,
                                "C4:a-mods-clock"),
                        broken("WorldWithoutDefaults", new ReferenceClockModel(), ClockVariants::worldWithoutDefaults,
                                "C4:default-clock-listed"),
                        broken("SleepMovesEveryClock", new ReferenceClockModel(), ClockVariants::sleepMovesEveryClock,
                                "C4:sleep-moves-default-clock-only"),
                        broken("SleepsWithoutAClock", new ReferenceClockModel(), ClockVariants::sleepsWithoutAClock,
                                "C4:no-default-clock-no-skip"),
                        broken("RateIgnoringExtrapolation", ClockVariants.rateIgnoringExtrapolation(), "C5:sync-agrees-exactly"),
                        broken("ForgetfulClient", ClockVariants.forgetfulClient(), "C5:sync-agrees-exactly"),
                        broken("QuantisedRate", ClockVariants.quantisedRate(), "C5:sync-within-one-tick"),
                        broken("ChangeSyncIgnoresRule", new ReferenceClockModel(), ClockVariants::changeSyncIgnoresRule,
                                "C5:join-and-change-agree"),
                        broken("RunningWhenStillSync", ClockVariants.runningWhenStillSync(), "C5:still-is-sent-still"),
                        broken("RemembersCalls", ClockVariants.remembersCalls(), "C6:deterministic"),
                        broken("NoOpClockModel", new NoOpClockModel(), "C1:integer-rate-advances")
                ));
        System.exit(exit);
    }
}
