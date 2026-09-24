package mycelium.data_catalog.v1.harness;

import mycelium.data_catalog.v1.model.Catalog;
import mycelium.data_catalog.v1.model.Parser;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_catalog.v1.model.Source;
import mycelium.harness.Check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/** data.catalog@1: the laws of a reloadable, validated set of named data entries (SPEC.md). */
public final class DataCatalogContractTests {
    private final Supplier<Catalog<Integer>> catalogs;

    public DataCatalogContractTests(Supplier<Catalog<Integer>> catalogs) {
        this.catalogs = catalogs;
    }

    /** Parses an integer; anything unparseable is rejected. */
    static final Parser<Integer> INTS = (id, text) -> Integer.parseInt(text.trim());

    static Source src(String id, String text, int pack) {
        return new Source(id, text, pack);
    }

    public Check run() {
        Check c = new Check();
        t1ValidLoadsInIdOrder(c);
        t2BadIsRejectedAlone(c);
        t3LaterPackWins(c);
        t4AtomicDuringReload(c);
        t5GenerationRises(c);
        t6ReloadReplacesEverything(c);
        t7NeverThrows(c);
        t8OldSnapshotsStay(c);
        t9Seeded(c);
        return c;
    }

    /** T1: every valid source loads, and entries iterate in id order whatever order they were offered. */
    private void t1ValidLoadsInIdOrder(Check c) {
        Snapshot<Integer> s = safeReload(c, catalogs.get(), List.of(src("b:y", "2", 0), src("a:z", "3", 0), src("a:x", "1", 0)), "T1");
        if (s == null) {
            return;
        }
        c.equal(List.of("a:x", "a:z", "b:y"), new ArrayList<>(s.entries().keySet()), "T1:id-order");
        c.that(s.rejected().isEmpty(), "T1:nothing-rejected");
    }

    /** T2: a bad source is rejected with its id; every other source still loads. */
    private void t2BadIsRejectedAlone(Check c) {
        Snapshot<Integer> s = safeReload(c, catalogs.get(), List.of(src("p:a", "1", 0), src("p:bad", "not a number", 0), src("p:c", "3", 0)), "T2");
        if (s == null) {
            return;
        }
        c.equal(2, s.entries().size(), "T2:others-load");
        c.that(s.rejected().size() == 1 && s.rejected().get(0).id().equals("p:bad"), "T2:bad-rejected-by-id");
    }

    /** T3: on the same id a later pack wins, however the list is ordered. */
    private void t3LaterPackWins(Check c) {
        for (List<Source> offered : List.of(
                List.of(src("p:x", "1", 0), src("p:x", "2", 1)),
                List.of(src("p:x", "2", 1), src("p:x", "1", 0)))) {
            Snapshot<Integer> s = safeReload(c, catalogs.get(), offered, "T3");
            if (s != null) {
                c.equal(2, s.entries().get("p:x"), "T3:later-pack-wins");
            }
        }
    }

    /** T4: while a reload runs, current() is still the whole old snapshot (nothing is published half-done). */
    private void t4AtomicDuringReload(Check c) {
        Catalog<Integer> cat = catalogs.get();
        Snapshot<Integer> before = safeReload(c, cat, List.of(src("o:a", "1", 0), src("o:b", "2", 0)), "T4");
        if (before == null) {
            return;
        }
        List<Snapshot<Integer>> seen = new ArrayList<>();
        Parser<Integer> watching = (id, text) -> {
            seen.add(cat.current());
            return Integer.parseInt(text.trim());
        };
        safeReloadWith(c, cat, List.of(src("n:a", "5", 0), src("n:b", "6", 0), src("n:c", "7", 0)), watching, "T4");
        for (Snapshot<Integer> mid : seen) {
            c.that(mid.generation() == before.generation() && mid.entries().equals(before.entries()), "T4:old-whole-until-done");
        }
        c.equal(3, cat.current().entries().size(), "T4:new-whole-after");
    }

    /** T5: each reload's generation is higher than the last. */
    private void t5GenerationRises(Check c) {
        Catalog<Integer> cat = catalogs.get();
        long last = cat.current().generation();
        for (int i = 0; i < 3; i++) {
            Snapshot<Integer> s = safeReload(c, cat, List.of(src("g:a", String.valueOf(i), 0)), "T5");
            if (s == null) {
                return;
            }
            c.that(s.generation() > last, "T5:generation-rises");
            last = s.generation();
        }
    }

    /** T6: a reload replaces everything: an entry whose file is gone is gone. */
    private void t6ReloadReplacesEverything(Check c) {
        Catalog<Integer> cat = catalogs.get();
        safeReload(c, cat, List.of(src("r:a", "1", 0), src("r:b", "2", 0)), "T6");
        Snapshot<Integer> s = safeReload(c, cat, List.of(src("r:b", "3", 0)), "T6");
        if (s != null) {
            c.that(!s.entries().containsKey("r:a"), "T6:removed-is-gone");
            c.equal(3, s.entries().get("r:b"), "T6:changed-is-new");
        }
        Snapshot<Integer> empty = safeReload(c, cat, List.of(), "T6");
        if (empty != null) {
            c.that(empty.entries().isEmpty(), "T6:empty-reload-empties");
        }
    }

    /** T7: whatever the parser throws (or returns null), reload returns: a catalog never takes the server down. */
    private void t7NeverThrows(Check c) {
        Parser<Integer> hostile = (id, text) -> {
            if (text.equals("boom")) {
                throw new IllegalStateException("boom");
            }
            if (text.equals("null")) {
                return null;
            }
            return Integer.parseInt(text);
        };
        try {
            Snapshot<Integer> s = catalogs.get().reload(List.of(src("h:a", "boom", 0), src("h:b", "null", 0), src("h:c", "4", 0)), hostile);
            c.that(s.entries().size() == 1 && s.rejected().size() == 2, "T7:rejected-not-thrown");
        } catch (RuntimeException e) {
            c.that(false, "T7:never-throws");
        }
    }

    /** T8: a snapshot someone holds is unchanged by later reloads, and cannot be changed by its holder. */
    private void t8OldSnapshotsStay(Check c) {
        Catalog<Integer> cat = catalogs.get();
        Snapshot<Integer> held = safeReload(c, cat, List.of(src("k:a", "1", 0)), "T8");
        safeReload(c, cat, List.of(src("k:b", "2", 0)), "T8");
        if (held == null) {
            return;
        }
        c.that(held.entries().containsKey("k:a") && !held.entries().containsKey("k:b"), "T8:held-unchanged");
        c.throwsAny(() -> held.entries().put("k:z", 9), "T8:held-unmodifiable");
    }

    /** T9: random packs, duplicates and bad files: loaded = later-pack winners that parse; rejected = those that don't. */
    private void t9Seeded(Check c) {
        Random r = new Random(0xCA7A);
        for (int round = 0; round < 60; round++) {
            List<Source> offered = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                String text = r.nextInt(5) == 0 ? "bad" : String.valueOf(r.nextInt(100));
                offered.add(src("s:" + r.nextInt(8), text, r.nextInt(4)));
            }
            Map<String, Source> winner = new HashMap<>();
            for (Source s : offered) {
                Source w = winner.get(s.id());
                if (w == null || s.packOrder() > w.packOrder()) {
                    winner.put(s.id(), s);
                }
            }
            // two files for one id in the same pack: which wins is not specified; leave them out of the check
            Map<String, Integer> ties = new HashMap<>();
            for (Source s : offered) {
                if (s.packOrder() == winner.get(s.id()).packOrder()) {
                    ties.merge(s.id(), 1, Integer::sum);
                }
            }
            Collections.shuffle(offered, r);
            Snapshot<Integer> snap = safeReload(c, catalogs.get(), offered, "T9");
            if (snap == null) {
                return;
            }
            for (Source w : winner.values()) {
                if (ties.get(w.id()) > 1) {
                    continue;
                }
                boolean good = !w.text().equals("bad");
                c.that(good == snap.entries().containsKey(w.id()), "T9:loaded-iff-winner-parses");
                c.that(!good == snap.rejected().stream().anyMatch(x -> x.id().equals(w.id())), "T9:rejected-iff-winner-bad");
                if (good) {
                    c.equal(Integer.parseInt(w.text()), snap.entries().get(w.id()), "T9:winner-value");
                }
            }
        }
    }

    private Snapshot<Integer> safeReload(Check c, Catalog<Integer> cat, List<Source> s, String law) {
        return safeReloadWith(c, cat, s, INTS, law);
    }

    private Snapshot<Integer> safeReloadWith(Check c, Catalog<Integer> cat, List<Source> s, Parser<Integer> p, String law) {
        try {
            return cat.reload(s, p);
        } catch (RuntimeException e) {
            c.that(false, law + ":reload-threw");
            return null;
        }
    }
}
