package mycelium.data_generation.v1.model;

import mycelium.data_catalog.v1.model.Rejection;
import mycelium.data_catalog.v1.model.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The reference generations (SPEC.md §4). One offer at a time: the offer's shape is checked, the assembler reads every
 * snapshot, every offered entry is accounted for, references are resolved to a fixed point (an optional kind's broken
 * entry leaves, and whatever referred to it after it), the survivors are hashed by {@link Hashing}, the assembler builds,
 * and only then is the one new generation swapped in, by one write. Anything that refuses the candidate returns the
 * live generation untouched with every finding; nothing the assembler throws escapes.
 */
public final class StandardGenerations<D, G> implements Generations<G> {
    private final Assembler<D, G> assembler;
    private volatile Optional<Generation<G>> current = Optional.empty();

    public StandardGenerations(Assembler<D, G> assembler) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    @Override
    public Optional<Generation<G>> current() {
        return current;
    }

    @Override
    public synchronized Outcome<G> offer(Map<String, ? extends Snapshot<?>> offered) {
        Optional<Generation<G>> live = current;
        List<Diagnostic> found = new ArrayList<>();
        try {
            return attempt(offered, live, found);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError vme) {
                throw vme;
            }
            found.add(Diagnostic.candidate(Severity.ERROR, "assembler:threw", String.valueOf(t)));
            return kept(live, found);
        }
    }

    private Outcome<G> attempt(Map<String, ? extends Snapshot<?>> offered, Optional<Generation<G>> live, List<Diagnostic> found) {
        String interpreter = Objects.requireNonNull(assembler.interpreter(), "the assembler's interpreter");
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Kind k : assembler.kinds()) {
            if (kinds.put(k.id(), k) != null) {
                throw new IllegalStateException("the assembler declares kind " + k.id() + " twice");
            }
        }

        // The offer's shape (G8): one snapshot for every declared kind, and nothing else.
        boolean refuse = false;
        Map<String, Snapshot<?>> snapshots = new LinkedHashMap<>();
        for (Kind k : kinds.values()) {
            Snapshot<?> s = offered == null ? null : offered.get(k.id());
            if (s == null) {
                found.add(Diagnostic.candidate(Severity.ERROR, "offer:missing-kind", "no snapshot was offered for kind " + k.id()));
                refuse = true;
            } else {
                snapshots.put(k.id(), s);
            }
        }
        if (offered != null) {
            for (String id : new TreeSet<>(offered.keySet())) {
                if (!kinds.containsKey(id)) {
                    found.add(Diagnostic.candidate(Severity.ERROR, "offer:unknown-kind", "a snapshot was offered for kind " + id + ", which the assembler does not declare"));
                    refuse = true;
                }
            }
        }
        if (refuse) {
            return kept(live, found);
        }

        // What was offered (G7): every entry, and every file the catalog rejected.
        Set<Ref> entries = new TreeSet<>();
        Set<Ref> broken = new TreeSet<>();
        for (Map.Entry<String, Snapshot<?>> e : snapshots.entrySet()) {
            for (String id : e.getValue().entries().keySet()) {
                entries.add(new Ref(e.getKey(), id));
            }
            for (Rejection r : e.getValue().rejected()) {
                Ref ref = new Ref(e.getKey(), r.id());
                broken.add(ref);
                found.add(Diagnostic.of(Severity.ERROR, ref, "catalog:rejected", r.reason()));
            }
        }

        Draft<D> draft = Objects.requireNonNull(assembler.read(Collections.unmodifiableMap(snapshots)), "the assembler's draft");
        for (Diagnostic d : draft.diagnostics()) {
            found.add(d);
            if (!d.isError()) {
                continue;
            }
            if (d.subject() == null || !kinds.containsKey(d.subject().kind())) {
                refuse = true;
            } else {
                broken.add(d.subject());
            }
        }

        // Accounting (G7): a part for every offered entry no ERROR names, and none for anything else.
        Map<Ref, Part> parts = new TreeMap<>();
        for (Part p : draft.parts()) {
            if (!entries.contains(p.ref())) {
                found.add(Diagnostic.of(Severity.ERROR, p.ref(), "assembler:unoffered-part", "the assembler read an entry no snapshot offered"));
                refuse = true;
            } else if (parts.putIfAbsent(p.ref(), p) != null) {
                found.add(Diagnostic.of(Severity.ERROR, p.ref(), "assembler:duplicate-part", "the assembler read this entry twice"));
                refuse = true;
            }
        }
        for (Ref r : entries) {
            if (!parts.containsKey(r) && !broken.contains(r)) {
                found.add(Diagnostic.of(Severity.ERROR, r, "assembler:unlisted-entry", "offered, but the assembler gave it neither a part nor a finding"));
                refuse = true;
            }
        }
        for (Ref r : broken) {
            if (kinds.get(r.kind()).policy() == Policy.REQUIRED) {
                refuse = true;
            }
        }

        // References (G2): the largest set of sound parts closed under reference. A required referrer refuses.
        Set<Ref> alive = new TreeSet<>(parts.keySet());
        alive.removeAll(broken);
        Set<Ref> left = new TreeSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Ref r : new ArrayList<>(alive)) {
                List<Ref> dangling = new ArrayList<>();
                for (Ref t : parts.get(r).references()) {
                    if (!alive.contains(t)) {
                        dangling.add(t);
                    }
                }
                if (dangling.isEmpty()) {
                    continue;
                }
                for (Ref t : dangling) {
                    found.add(Diagnostic.of(Severity.ERROR, r, "reference:dangling", "names " + t + ", which " + why(t, entries, broken, left)));
                }
                alive.remove(r);
                left.add(r);
                changed = true;
                if (kinds.get(r.kind()).policy() == Policy.REQUIRED) {
                    refuse = true;
                }
            }
        }
        if (refuse) {
            return kept(live, found);
        }

        // One name (G3), one number (G4), then the whole-candidate build.
        TreeMap<Ref, String> manifest = new TreeMap<>();
        for (Ref r : alive) {
            manifest.put(r, Hashing.fingerprint(parts.get(r).content()));
        }
        String hash = Hashing.generation(interpreter, manifest);
        long number = live.map(Generation::number).orElse(0L) + 1;
        Assembly<G> assembly = Objects.requireNonNull(
                assembler.build(draft.value(), new Resolved(number, hash, interpreter, manifest, sorted(found))),
                "the assembler's assembly");
        found.addAll(assembly.diagnostics());
        boolean errors = assembly.diagnostics().stream().anyMatch(Diagnostic::isError);
        if (assembly instanceof Assembly.Refused<G> || errors) {
            if (!errors) {
                found.add(Diagnostic.candidate(Severity.ERROR, "assembler:refused", "the build refused the candidate without saying why"));
            }
            return kept(live, found);
        }
        Generation<G> next = new Generation<>(number, hash, interpreter, manifest, ((Assembly.Built<G>) assembly).value(), sorted(found));
        current = Optional.of(next); // G1: the one write that publishes
        return new Outcome.Published<>(next);
    }

    private static String why(Ref t, Set<Ref> entries, Set<Ref> broken, Set<Ref> left) {
        if (left.contains(t)) {
            return "was left out for a dangling reference of its own";
        }
        if (broken.contains(t)) {
            return "is broken";
        }
        if (entries.contains(t)) {
            return "has no part";
        }
        return "was not offered";
    }

    private Outcome<G> kept(Optional<Generation<G>> live, List<Diagnostic> found) {
        return new Outcome.Kept<>(live, sorted(found));
    }

    private static List<Diagnostic> sorted(List<Diagnostic> found) {
        List<Diagnostic> out = new ArrayList<>(found);
        out.sort(Diagnostic.ORDER);
        return out;
    }
}
