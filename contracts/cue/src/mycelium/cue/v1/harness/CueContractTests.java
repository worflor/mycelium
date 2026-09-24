package mycelium.cue.v1.harness;

import mycelium.cue.v1.fixtures.FixedProfileMap;
import mycelium.cue.v1.model.Cue;
import mycelium.cue.v1.model.CueOutcome;
import mycelium.cue.v1.model.CueScheduler;
import mycelium.cue.v1.model.ProfileId;
import mycelium.cue.v1.model.ProfileMap;
import mycelium.cue.v1.model.Receipt;
import mycelium.cue.v1.model.Tx;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public final class CueContractTests {
    private final Function<ProfileMap, CueScheduler> schedulerFactory;

    public CueContractTests(Function<ProfileMap, CueScheduler> schedulerFactory) {
        this.schedulerFactory = schedulerFactory;
    }

    public Check run() {
        Check check = new Check();
        t1PositiveResolution(check);
        t2FallbackResolution(check);
        t3UnsupportedNoMappingNoFallback(check);
        t4NoCueBeforeOuterCommit(check);
        t5FinalityOnAbort(check);
        t6ExactlyOneCuePerReceipt(check);
        t7SeededEveryReceiptYieldsItsCue(check);
        return check;
    }

    private void t1PositiveResolution(Check c) {
        ProfileId profile = new ProfileId("forge");
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.of("spark", "spark_v2")));
        CueScheduler scheduler = schedulerFactory.apply(map);
        Cue cue = new Cue("particle", "forge", "spark");
        MemorySink sink = new MemorySink();
        Tx tx = Tx.open();

        CueOutcome outcome = scheduler.schedule(tx, new Receipt("op-1"), cue, sink);
        c.that(outcome instanceof CueOutcome.Scheduled, "T1:scheduled");
        if (outcome instanceof CueOutcome.Scheduled scheduled) {
            c.equal("spark_v2", scheduled.cue().key(), "T1:resolved-key");
        }
        c.that(sink.events().isEmpty(), "T1:no-cue-before-commit");
        tx.commit();
        c.that(sink.events().size() == 1, "T1:feedback-once");
        if (sink.events().size() == 1) {
            c.equal("spark_v2", sink.events().get(0).key(), "T1:emitted-resolved-key");
        }
    }

    private void t2FallbackResolution(Check c) {
        ProfileId profile = new ProfileId("sanctum");
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.of(ProfileMap.FALLBACK_KEY, "boom_default")));
        CueScheduler scheduler = schedulerFactory.apply(map);
        Cue cue = new Cue("particle", "sanctum", "boom");
        MemorySink sink = new MemorySink();
        Tx tx = Tx.open();

        CueOutcome outcome = scheduler.schedule(tx, new Receipt("op-2"), cue, sink);
        c.that(outcome instanceof CueOutcome.Scheduled, "T2:fallback-scheduled");
        if (outcome instanceof CueOutcome.Scheduled scheduled) {
            c.equal("boom_default", scheduled.cue().key(), "T2:fallback-resolved-key");
        }
        tx.commit();
        c.that(sink.events().size() == 1, "T2:fallback-emitted");
        if (sink.events().size() == 1) {
            c.equal("boom_default", sink.events().get(0).key(), "T2:fallback-emitted-key");
        }
    }

    private void t3UnsupportedNoMappingNoFallback(Check c) {
        ProfileId profile = new ProfileId("bare");
        ProfileMap map = new FixedProfileMap(Map.of());
        CueScheduler scheduler = schedulerFactory.apply(map);
        Cue cue = new Cue("particle", "bare", "unknown");
        MemorySink sink = new MemorySink();
        Tx tx = Tx.open();

        CueOutcome outcome = scheduler.schedule(tx, new Receipt("op-3"), cue, sink);
        c.that(outcome instanceof CueOutcome.Unsupported, "T3:unsupported");
        tx.commit();
        c.that(sink.events().isEmpty(), "T3:never-emitted");
    }

    private void t4NoCueBeforeOuterCommit(Check c) {
        ProfileId profile = new ProfileId("forge");
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.of("spark", "spark_v2")));
        CueScheduler scheduler = schedulerFactory.apply(map);
        Cue cue = new Cue("particle", "forge", "spark");
        MemorySink sink = new MemorySink();

        Tx outer = Tx.open();
        Tx nested = outer.openNested();
        scheduler.schedule(nested, new Receipt("op-4"), cue, sink);
        nested.commit();
        c.that(sink.events().isEmpty(), "T4:no-cue-before-outer-commit");
        outer.commit();
        c.that(sink.events().size() == 1, "T4:cue-after-outer-commit");
    }

    private void t5FinalityOnAbort(Check c) {
        ProfileId profile = new ProfileId("forge");
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.of("spark", "spark_v2")));
        CueScheduler scheduler = schedulerFactory.apply(map);
        Cue cue = new Cue("particle", "forge", "spark");
        MemorySink sink = new MemorySink();

        Tx outer = Tx.open();
        Tx nested = outer.openNested();
        scheduler.schedule(nested, new Receipt("op-5"), cue, sink);
        nested.commit();
        outer.abort();
        c.that(sink.events().isEmpty(), "T5:no-cue-after-abort");
    }

    private void t6ExactlyOneCuePerReceipt(Check c) {
        ProfileId profile = new ProfileId("forge");
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.of("spark", "spark_v2", "boom", "boom_v2")));
        CueScheduler scheduler = schedulerFactory.apply(map);
        MemorySink sink = new MemorySink();

        Tx tx1 = Tx.open();
        scheduler.schedule(tx1, new Receipt("op-6a"), new Cue("particle", "forge", "spark"), sink);
        tx1.commit();

        Tx tx2 = Tx.open();
        scheduler.schedule(tx2, new Receipt("op-6b"), new Cue("particle", "forge", "boom"), sink);
        tx2.commit();

        c.that(sink.events().size() == 2, "T6:exactly-one-cue-per-receipt");
    }

    private void t7SeededEveryReceiptYieldsItsCue(Check c) {
        Seeded seeded = new Seeded(9001);
        ProfileId profile = new ProfileId("arcane");
        Map<String, String> table = new HashMap<>();
        int total = 25;
        String[] resolved = new String[total];
        for (int i = 0; i < total; i++) {
            String key = "key-" + i;
            String value = "resolved-" + seeded.nextInt(1_000_000);
            table.put(key, value);
            resolved[i] = value;
        }
        ProfileMap map = new FixedProfileMap(Map.of(profile, Map.copyOf(table)));
        CueScheduler scheduler = schedulerFactory.apply(map);
        MemorySink sink = new MemorySink();

        for (int i = 0; i < total; i++) {
            Tx tx = Tx.open();
            CueOutcome outcome = scheduler.schedule(tx, new Receipt("op-7-" + i),
                    new Cue("particle", "arcane", "key-" + i), sink);
            c.that(outcome instanceof CueOutcome.Scheduled, "T7:scheduled-" + i);
            tx.commit();
        }

        c.that(sink.events().size() == total, "T7:every-receipt-emitted-once");
        for (int i = 0; i < total; i++) {
            final int idx = i;
            boolean found = sink.events().stream().anyMatch(e -> e.key().equals(resolved[idx]));
            c.that(found, "T7:property-" + i);
        }
    }
}
