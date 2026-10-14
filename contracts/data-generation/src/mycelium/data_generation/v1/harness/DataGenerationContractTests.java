package mycelium.data_generation.v1.harness;

import mycelium.data_generation.v1.harness.GenerationWorld.Offer;
import mycelium.data_generation.v1.harness.GenerationWorld.Rec;
import mycelium.data_generation.v1.harness.GenerationWorld.TestAssembler;
import mycelium.data_generation.v1.harness.GenerationWorld.World;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Diagnostic;
import mycelium.data_generation.v1.model.Generation;
import mycelium.data_generation.v1.model.Generations;
import mycelium.data_generation.v1.model.Hashing;
import mycelium.data_generation.v1.model.Outcome;
import mycelium.data_generation.v1.model.Ref;
import mycelium.data_generation.v1.model.Severity;
import mycelium.harness.Check;
import mycelium.harness.Seeded;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static mycelium.data_generation.v1.harness.GenerationWorld.BINDINGS;
import static mycelium.data_generation.v1.harness.GenerationWorld.RULES;
import static mycelium.data_generation.v1.harness.GenerationWorld.STRUCTURES;
import static mycelium.data_generation.v1.harness.GenerationWorld.content;
import static mycelium.data_generation.v1.harness.GenerationWorld.rec;
import static mycelium.data_generation.v1.harness.GenerationWorld.ref;

/** data.generation@1: the laws of one coherent, named view over several catalogs (SPEC.md). */
public final class DataGenerationContractTests {
    /**
     * The golden values (G3): the canonical hashing of {@link #golden()} under interpreter {@code "1"}, and of nothing.
     * Computed by the reference and, independently, by a few lines of Python over the encoding SPEC.md §3 states.
     */
    static final String GOLDEN = "89056cba5cc19999a91ea6d2a207080b18114ed619b04e760b8289bb9e70d60f";
    static final String GOLDEN_EMPTY = "053b75d5f0557244873b94c69f143944131710a38689284246328e9b99e810c7";

    private final Function<Assembler<Map<Ref, Rec>, World>, Generations<World>> factory;

    public DataGenerationContractTests(Function<Assembler<Map<Ref, Rec>, World>, Generations<World>> factory) {
        this.factory = factory;
    }

    public Check run() {
        Check c = new Check();
        p1ConsistentIsPublished(c);
        g1WholeOrNothing(c);
        g2ReferencesResolve(c);
        g2Seeded(c);
        g3OneName(c);
        g4NumbersRise(c);
        g5HeldIsHeld(c);
        g6EveryFindingAtOnce(c);
        g7Accounted(c);
        g8Contained(c);
        return c;
    }

    /** Rules, structures that name them, a binding that names a structure: every reference resolves. */
    static Offer worldA() {
        return new Offer()
                .put(RULES, "t:r1", rec("one"))
                .put(RULES, "t:r2", rec("two").asOdd())
                .put(STRUCTURES, "t:s1", rec("sun", ref(RULES, "t:r1")))
                .put(STRUCTURES, "t:s2", rec("moon"))
                .put(BINDINGS, "t:b1", rec("b1", ref(STRUCTURES, "t:s1")));
    }

    /** World A retuned, with one more structure: consistent, and different. */
    static Offer worldB() {
        return worldA()
                .put(RULES, "t:r1", rec("one, retuned"))
                .put(STRUCTURES, "t:s3", rec("star", ref(RULES, "t:r2")));
    }

    /** A required entry naming nothing: refused. */
    static Offer refused() {
        return worldB().put(RULES, "t:r3", rec("three", ref(STRUCTURES, "t:nowhere")));
    }

    /** The golden candidate: ids whose UTF-16 order and code point order differ. */
    static Offer golden() {
        return new Offer()
                .put(RULES, "example:ruleset/sample", rec("{\"limitPermille\":0}"))
                .put(STRUCTURES, "a�", rec("x"))
                .put(STRUCTURES, "a😀", rec("y", ref(RULES, "example:ruleset/sample")));
    }

    // ---- P1 ----

    /** P1: a consistent candidate is published, whole, and becomes current. */
    private void p1ConsistentIsPublished(Check c) {
        Generations<World> gens = factory.apply(new TestAssembler());
        Outcome<World> out = offer(c, gens, worldA());
        c.that(out instanceof Outcome.Published<World>, "P1:consistent-published");
        c.that(gens.current().isPresent(), "P1:consistent-published");
        if (out instanceof Outcome.Published<World> p) {
            c.equal(worldA().offeredRefs(), new TreeSet<>(p.generation().manifest().keySet()), "P1:every-entry-published");
            c.that(has(p.generation().diagnostics(), ref(RULES, "t:r2"), "test:warn"), "P1:warnings-carried");
        }
    }

    // ---- G1 ----

    /** G1: current() is never a part of a candidate nor a mix of two; a refusal leaves the live generation as it was. */
    private void g1WholeOrNothing(Check c) {
        TestAssembler a = new TestAssembler();
        Generations<World> gens = factory.apply(a);
        offer(c, gens, worldA());
        List<Optional<Generation<World>>> seen = new ArrayList<>();
        a.probe = () -> seen.add(gens.current());

        // consistent; refused by a dangling required reference; refused by the build (no rules); consistent again
        Offer[] offers = {worldB(), refused(), new Offer().put(STRUCTURES, "t:s2", rec("moon")), worldA()};
        boolean[] refusal = {false, true, true, false};
        for (int i = 0; i < offers.length; i++) {
            seen.clear();
            Optional<Generation<World>> before = gens.current();
            Outcome<World> out = offer(c, gens, offers[i]);
            for (Optional<Generation<World>> mid : seen) {
                c.that(mid.equals(before), "G1:never-partial");
                mid.ifPresent(g -> coherent(c, g));
            }
            gens.current().ifPresent(g -> coherent(c, g));
            if (out != null && refusal[i]) {
                c.that(out instanceof Outcome.Kept<World>, "G1:refused-is-kept");
            }
        }
    }

    // ---- G2 ----

    /** G2: published references resolve; a required referrer refuses; an optional one leaves, transitively, and is named. */
    private void g2ReferencesResolve(Check c) {
        Outcome<World> out = offer(c, factory.apply(new TestAssembler()), refused());
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), ref(RULES, "t:r3"), "reference:dangling"),
                "G2:required-dangling-refuses");

        Ref b2 = ref(BINDINGS, "t:b2");
        Ref s4 = ref(STRUCTURES, "t:s4");
        out = offer(c, factory.apply(new TestAssembler()), worldA()
                .put(BINDINGS, "t:b2", rec("b2", s4))
                .put(STRUCTURES, "t:s4", rec("s4", ref(STRUCTURES, "t:gone"))));
        leftOut(c, out, Set.of(b2, s4), "G2:optional-dropped-transitively", "G2:dropped-reported");

        Ref x = ref(STRUCTURES, "t:x");
        Ref y = ref(STRUCTURES, "t:y");
        out = offer(c, factory.apply(new TestAssembler()), worldA()
                .put(STRUCTURES, "t:x", rec("x", y))
                .put(STRUCTURES, "t:y", rec("y", x, ref(BINDINGS, "t:nothing"))));
        leftOut(c, out, Set.of(x, y), "G2:optional-dropped-transitively", "G2:dropped-reported");

        Ref s5 = ref(STRUCTURES, "t:s5");
        Ref b5 = ref(BINDINGS, "t:b5");
        out = offer(c, factory.apply(new TestAssembler()), worldA()
                .put(STRUCTURES, "t:s5", rec("s5").asBad())
                .put(BINDINGS, "t:b5", rec("b5", s5)));
        leftOut(c, out, Set.of(s5, b5), "G2:broken-optional-left-out", "G2:dropped-reported");
    }

    private void leftOut(Check c, Outcome<World> out, Set<Ref> gone, String law, String reported) {
        if (!(out instanceof Outcome.Published<World> p)) {
            c.fail(law + " (the candidate was refused)");
            return;
        }
        Generation<World> g = p.generation();
        Set<Ref> named = subjects(g.diagnostics());
        for (Ref r : gone) {
            c.that(!g.manifest().containsKey(r), law);
            c.that(named.contains(r), reported);
        }
        c.that(g.manifest().keySet().containsAll(worldA().offeredRefs()), law);
    }

    /** G2, seeded: the published entries are exactly those from which only present, sound entries are reachable. */
    private void g2Seeded(Check c) {
        Seeded r = new Seeded(0xD47A6E4EL);
        String[] kinds = {RULES, STRUCTURES, BINDINGS};
        int published = 0;
        int kept = 0;
        for (int round = 0; round < 300; round++) {
            Offer o = new Offer();
            Map<Ref, Rec> present = new TreeMap<>();
            boolean rulesRejected = false;
            for (String kind : kinds) {
                boolean rules = kind.equals(RULES);
                for (int i = 0; i < 4; i++) {
                    if (r.nextInt(4) == 0) {
                        continue;
                    }
                    String id = "s:" + i;
                    if (r.nextInt(rules ? 60 : 10) == 0) {
                        o.reject(kind, id, "unreadable");
                        rulesRejected |= rules;
                        continue;
                    }
                    SortedSet<Ref> refs = new TreeSet<>();
                    int n = rules ? (r.nextInt(5) == 0 ? 1 : 0) : r.nextInt(3);
                    for (int j = 0; j < n; j++) {
                        String to = rules ? RULES : kinds[r.nextInt(3)];
                        refs.add(ref(to, "s:" + r.nextInt(5))); // s:4 is never offered
                    }
                    Rec rec = new Rec("v" + r.nextInt(1000), refs, r.nextInt(rules ? 60 : 8) == 0, false);
                    o.put(kind, id, rec);
                    present.put(ref(kind, id), rec);
                }
            }
            Set<Ref> survivors = new TreeSet<>();
            for (Ref e : present.keySet()) {
                if (onlySoundReachable(e, present)) {
                    survivors.add(e);
                }
            }
            boolean refuse = rulesRejected
                    || present.keySet().stream().anyMatch(e -> e.kind().equals(RULES) && !survivors.contains(e))
                    || survivors.stream().noneMatch(e -> e.kind().equals(RULES));
            Outcome<World> out = offer(c, factory.apply(new TestAssembler()), o);
            if (out == null) {
                return;
            }
            c.that((out instanceof Outcome.Kept<World>) == refuse, "G2:seeded-refusal");
            if (out instanceof Outcome.Published<World> p) {
                c.equal(survivors, new TreeSet<>(p.generation().manifest().keySet()), "G2:seeded-survivors");
                published++;
            } else {
                kept++;
            }
        }
        c.that(published >= 50 && kept >= 50, "G2:seeded-covers-both (published " + published + ", kept " + kept + ")");
    }

    private static boolean onlySoundReachable(Ref from, Map<Ref, Rec> present) {
        Deque<Ref> todo = new ArrayDeque<>(List.of(from));
        Set<Ref> seen = new TreeSet<>();
        while (!todo.isEmpty()) {
            Ref at = todo.pop();
            if (!seen.add(at)) {
                continue;
            }
            Rec rec = present.get(at);
            if (rec == null || rec.bad()) {
                return false;
            }
            todo.addAll(rec.refs());
        }
        return true;
    }

    // ---- G3 ----

    /** G3: the hash is the fixed canonical hashing of the published contents and the interpreter, and of nothing else. */
    private void g3OneName(Check c) {
        String base = hashOf(c, factory.apply(new TestAssembler()), worldA());
        TestAssembler reversed = new TestAssembler();
        reversed.reverse = true;
        String listedBackwards = hashOf(c, factory.apply(reversed), worldA());
        String mapReordered = hashOf(c, factory.apply(new TestAssembler()), worldA().order(STRUCTURES, BINDINGS, RULES));
        Generations<World> twice = factory.apply(new TestAssembler());
        hashOf(c, twice, worldA());
        String again = hashOf(c, twice, worldA());
        if (base == null) {
            return;
        }
        c.equal(base, listedBackwards, "G3:order-independent");
        c.equal(base, mapReordered, "G3:order-independent");
        c.equal(base, again, "G3:order-independent");

        String retuned = hashOf(c, factory.apply(new TestAssembler()), worldA().put(STRUCTURES, "t:s2", rec("moon, retuned")));
        String renamed = hashOf(c, factory.apply(new TestAssembler()), worldA().remove(STRUCTURES, "t:s2").put(STRUCTURES, "t:s9", rec("moon")));
        String moved = hashOf(c, factory.apply(new TestAssembler()), worldA().remove(STRUCTURES, "t:s2").put(BINDINGS, "t:s2", rec("moon")));
        c.that(!base.equals(retuned), "G3:content-sensitive");
        c.that(!base.equals(renamed), "G3:content-sensitive");
        c.that(!base.equals(moved), "G3:content-sensitive");

        TestAssembler two = new TestAssembler();
        two.interpreter = "2";
        c.that(!base.equals(hashOf(c, factory.apply(two), worldA())), "G3:interpreter-sensitive");

        c.equal(GOLDEN, hashOf(c, factory.apply(new TestAssembler()), golden()), "G3:golden");
        c.equal(GOLDEN, Hashing.generation("1", manifestOf(golden())), "G3:golden");
        c.equal(GOLDEN_EMPTY, Hashing.generation("1", Map.of()), "G3:golden");
        c.equal("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.fingerprint(""), "G3:golden");
    }

    private static Map<Ref, String> manifestOf(Offer o) {
        Map<Ref, String> m = new TreeMap<>();
        o.recs().forEach((r, rec) -> m.put(r, Hashing.fingerprint(content(rec))));
        return m;
    }

    // ---- G4 ----

    /** G4: numbers rise by one with each publication; a refusal takes none. */
    private void g4NumbersRise(Check c) {
        Generations<World> gens = factory.apply(new TestAssembler());
        Long n1 = number(offer(c, gens, worldA()));
        offer(c, gens, refused());
        Long n2 = number(offer(c, gens, worldB()));
        Long n3 = number(offer(c, gens, worldA()));
        if (n1 == null || n2 == null || n3 == null) {
            return;
        }
        c.that(n1 < n2 && n2 < n3, "G4:monotonic");
        c.that(n1 == 1 && n2 == 2 && n3 == 3, "G4:consecutive");
    }

    private static Long number(Outcome<World> out) {
        return out instanceof Outcome.Published<World> p ? p.generation().number() : null;
    }

    // ---- G5 ----

    /** G5: a generation someone holds is unchanged by later offers, and cannot be changed by its holder. */
    private void g5HeldIsHeld(Check c) {
        Generations<World> gens = factory.apply(new TestAssembler());
        if (!(offer(c, gens, worldA()) instanceof Outcome.Published<World> p)) {
            return;
        }
        Generation<World> held = p.generation();
        long number = held.number();
        String hash = held.hash();
        Map<Ref, String> manifest = new TreeMap<>(held.manifest());
        World value = held.value();
        List<Diagnostic> diagnostics = new ArrayList<>(held.diagnostics());
        offer(c, gens, worldB());
        offer(c, gens, refused());
        c.that(held.number() == number && held.hash().equals(hash) && held.manifest().equals(manifest)
                && held.value().equals(value) && held.diagnostics().equals(diagnostics), "G5:immutable");
        c.throwsAny(() -> held.manifest().put(ref(RULES, "t:z"), "z"), "G5:immutable");
        c.throwsAny(() -> held.diagnostics().add(Diagnostic.candidate(Severity.INFO, "z", "z")), "G5:immutable");
    }

    // ---- G6 ----

    /** G6: a refused candidate names every problem found, each with its subject and code. */
    private void g6EveryFindingAtOnce(Check c) {
        TestAssembler a = new TestAssembler();
        a.candidateError = "the rules disagree with themselves";
        Outcome<World> out = offer(c, factory.apply(a), worldA()
                .put(RULES, "t:r2", rec("two").asBad())
                .put(RULES, "t:r3", rec("three", ref(STRUCTURES, "t:nowhere")))
                .reject(RULES, "t:r9", "unreadable")
                .put(BINDINGS, "t:b4", rec("b4", ref(STRUCTURES, "t:gone"))));
        if (!(out instanceof Outcome.Kept<World> k)) {
            c.fail("G6:all-findings (the candidate was published)");
            return;
        }
        List<Diagnostic> d = k.diagnostics();
        c.that(has(d, null, "test:candidate"), "G6:all-findings");
        c.that(has(d, ref(RULES, "t:r2"), "test:bad"), "G6:all-findings");
        c.that(has(d, ref(RULES, "t:r3"), "reference:dangling"), "G6:all-findings");
        c.that(has(d, ref(RULES, "t:r9"), "catalog:rejected"), "G6:all-findings");
        c.that(has(d, ref(BINDINGS, "t:b4"), "reference:dangling"), "G6:all-findings");
        for (Diagnostic x : d) {
            c.that(!x.code().isEmpty() && !x.message().isEmpty(), "G6:named");
        }
    }

    // ---- G7 ----

    /** G7: every offered entry is published or named; nothing is published that was not offered. */
    private void g7Accounted(Check c) {
        Ref s2 = ref(STRUCTURES, "t:s2");
        TestAssembler forgetful = new TestAssembler();
        forgetful.omit = s2;
        Outcome<World> out = offer(c, factory.apply(forgetful), worldA());
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), s2, "assembler:unlisted-entry"), "G7:unlisted-refuses");

        Ref ghost = ref(STRUCTURES, "t:ghost");
        TestAssembler inventive = new TestAssembler();
        inventive.invent = ghost;
        out = offer(c, factory.apply(inventive), worldA());
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), ghost, "assembler:unoffered-part"), "G7:unoffered-refuses");

        out = offer(c, factory.apply(new TestAssembler()), worldA().reject(RULES, "t:r9", "unreadable"));
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), ref(RULES, "t:r9"), "catalog:rejected"), "G7:required-rejection-refuses");

        out = offer(c, factory.apply(new TestAssembler()), worldA().put(RULES, "t:r2", rec("two").asBad()));
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), ref(RULES, "t:r2"), "test:bad"), "G7:required-error-refuses");

        Ref s9 = ref(STRUCTURES, "t:s9");
        Ref b9 = ref(BINDINGS, "t:b9");
        out = offer(c, factory.apply(new TestAssembler()), worldA().reject(STRUCTURES, "t:s9", "unreadable").put(BINDINGS, "t:b9", rec("b9", s9)));
        leftOut(c, out, Set.of(s9, b9), "G7:optional-rejection-left-out", "G7:optional-rejection-left-out");
        if (out instanceof Outcome.Published<World> p) {
            c.that(has(p.generation().diagnostics(), s9, "catalog:rejected"), "G7:optional-rejection-left-out");
        }
    }

    // ---- G8 ----

    /** G8: whatever the assembler throws, and whatever shape the offer has, an offer returns and the live one serves. */
    private void g8Contained(Check c) {
        TestAssembler a = new TestAssembler();
        Generations<World> gens = factory.apply(a);
        offer(c, gens, worldA());
        a.throwInRead = true;
        Outcome<World> out = offer(c, gens, worldB());
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), null, "assembler:threw"), "G8:threw-is-kept");
        a.throwInRead = false;
        a.throwInBuild = true;
        out = offer(c, gens, worldB());
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), null, "assembler:threw"), "G8:threw-is-kept");
        a.throwInBuild = false;

        out = offer(c, gens, worldB().order(RULES, STRUCTURES));
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), null, "offer:missing-kind"), "G8:offer-shape");
        out = offer(c, gens, worldB().extra("decks"));
        c.that(out instanceof Outcome.Kept<World> k && has(k.diagnostics(), null, "offer:unknown-kind"), "G8:offer-shape");
    }

    // ---- what every outcome is held to ----

    /**
     * Offers, catching what escapes (G8), and holds the outcome to the laws every outcome keeps: a publication is
     * current, coherent, resolved, canonically hashed and accounted for; a refusal leaves the live generation as it was
     * and says why.
     */
    private Outcome<World> offer(Check c, Generations<World> gens, Offer offer) {
        Optional<Generation<World>> before = gens.current();
        Outcome<World> out;
        try {
            out = gens.offer(offer.snapshots());
        } catch (RuntimeException e) {
            c.fail("G8:never-throws (" + e + ")");
            return null;
        }
        if (out == null) {
            c.fail("G8:never-throws (no outcome)");
            return null;
        }
        if (out instanceof Outcome.Published<World> p) {
            Generation<World> g = p.generation();
            c.that(gens.current().equals(Optional.of(g)), "G1:published-is-current");
            coherent(c, g);
            Map<Ref, Rec> recs = offer.recs();
            for (Map.Entry<Ref, String> e : g.manifest().entrySet()) {
                Rec rec = recs.get(e.getKey());
                c.that(rec != null, "G7:nothing-invented");
                if (rec == null) {
                    continue;
                }
                for (Ref t : rec.refs()) {
                    c.that(g.manifest().containsKey(t), "G2:no-dangling");
                }
                c.equal(Hashing.fingerprint(content(rec)), e.getValue(), "G3:fixed-hashing");
            }
            c.equal(Hashing.generation(g.interpreter(), g.manifest()), g.hash(), "G3:fixed-hashing");
            Set<Ref> named = subjects(g.diagnostics());
            for (Ref r : offer.offeredRefs()) {
                c.that(g.manifest().containsKey(r) || named.contains(r), "G7:accounted");
            }
        } else {
            Outcome.Kept<World> k = (Outcome.Kept<World>) out;
            c.that(k.current().equals(before) && gens.current().equals(before), "G1:refused-keeps-current");
            c.that(!k.diagnostics().isEmpty(), "G6:refusal-says-why");
        }
        return out;
    }

    /** G1: a generation's name (number, hash, manifest) and its value come from one candidate. */
    private static void coherent(Check c, Generation<World> g) {
        World w = g.value();
        c.that(w.number() == g.number() && w.hash().equals(g.hash()) && w.payloads().keySet().equals(g.manifest().keySet()),
                "G1:never-mixed");
    }

    private String hashOf(Check c, Generations<World> gens, Offer offer) {
        Outcome<World> out = offer(c, gens, offer);
        if (out instanceof Outcome.Published<World> p) {
            return p.generation().hash();
        }
        c.fail("P1:consistent-published (a consistent candidate was not published)");
        return null;
    }

    private static boolean has(List<Diagnostic> found, Ref subject, String code) {
        for (Diagnostic d : found) {
            if (Objects.equals(d.subject(), subject) && d.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    private static Set<Ref> subjects(List<Diagnostic> found) {
        Set<Ref> out = new TreeSet<>();
        for (Diagnostic d : found) {
            if (d.subject() != null) {
                out.add(d.subject());
            }
        }
        return out;
    }
}
