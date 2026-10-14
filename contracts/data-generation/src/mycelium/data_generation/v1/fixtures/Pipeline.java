package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Rejection;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Assembly;
import mycelium.data_generation.v1.model.Diagnostic;
import mycelium.data_generation.v1.model.Draft;
import mycelium.data_generation.v1.model.Generation;
import mycelium.data_generation.v1.model.Generations;
import mycelium.data_generation.v1.model.Hashing;
import mycelium.data_generation.v1.model.Kind;
import mycelium.data_generation.v1.model.Outcome;
import mycelium.data_generation.v1.model.Part;
import mycelium.data_generation.v1.model.Policy;
import mycelium.data_generation.v1.model.Ref;
import mycelium.data_generation.v1.model.Resolved;
import mycelium.data_generation.v1.model.Severity;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Not a matrix entry: the reference algorithm ({@code StandardGenerations}) copied with a hook at each step, so that
 * each broken implementation is the one step it gets wrong and nothing else. Every hook's default is the reference's
 * behaviour; a subclass that overrides none of them keeps every law.
 */
public abstract class Pipeline<D, G> implements Generations<G> {
    protected final Assembler<D, G> assembler;
    protected volatile Optional<Generation<G>> current = Optional.empty();

    protected Pipeline(Assembler<D, G> assembler) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    // ---- hooks: the reference's behaviour unless overridden ----

    /** Whether what the assembler throws is caught (G8). */
    protected boolean contain() {
        return true;
    }

    /** Whether every offered entry is accounted for (G7). */
    protected boolean account() {
        return true;
    }

    /** Whether references are resolved at all (G2). */
    protected boolean checkReferences() {
        return true;
    }

    /** Whether resolution runs to a fixed point (G2), or one pass against the entries as read. */
    protected boolean transitive() {
        return true;
    }

    /** Whether a refused candidate stays refused (G1). */
    protected boolean honourRefusal() {
        return true;
    }

    protected String fingerprint(Part part) {
        return Hashing.fingerprint(part.content());
    }

    /** The hash (G3), given what an implementation could wrongly reach for: the parts as listed, the offer as ordered. */
    protected String hash(String interpreter, SortedMap<Ref, String> manifest, List<Part> listed, Map<String, ? extends Snapshot<?>> offered) {
        return Hashing.generation(interpreter, manifest);
    }

    protected long number(Optional<Generation<G>> live) {
        return live.map(Generation::number).orElse(0L) + 1;
    }

    /** The findings a report carries (G6). */
    protected List<Diagnostic> report(List<Diagnostic> found) {
        List<Diagnostic> out = new ArrayList<>(found);
        out.sort(Diagnostic.ORDER);
        return out;
    }

    /** Called once the number and hash are known, before the build. */
    protected void beforeBuild(long number, String hash, String interpreter, SortedMap<Ref, String> manifest) {
    }

    protected void publish(Generation<G> next) {
        current = Optional.of(next);
    }

    protected Outcome<G> kept(Optional<Generation<G>> live, List<Diagnostic> found) {
        return new Outcome.Kept<>(live, report(found));
    }

    // ---- the reference algorithm ----

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
        } catch (RuntimeException t) {
            if (!contain()) {
                throw t;
            }
            found.add(Diagnostic.candidate(Severity.ERROR, "assembler:threw", String.valueOf(t)));
            return kept(live, found);
        }
    }

    private Outcome<G> attempt(Map<String, ? extends Snapshot<?>> offered, Optional<Generation<G>> live, List<Diagnostic> found) {
        String interpreter = Objects.requireNonNull(assembler.interpreter(), "interpreter");
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (Kind k : assembler.kinds()) {
            kinds.put(k.id(), k);
        }
        boolean refuse = false;
        Map<String, Snapshot<?>> snapshots = new LinkedHashMap<>();
        for (Kind k : kinds.values()) {
            Snapshot<?> s = offered == null ? null : offered.get(k.id());
            if (s == null) {
                found.add(Diagnostic.candidate(Severity.ERROR, "offer:missing-kind", "no snapshot for " + k.id()));
                refuse = true;
            } else {
                snapshots.put(k.id(), s);
            }
        }
        if (offered != null) {
            for (String id : new TreeSet<>(offered.keySet())) {
                if (!kinds.containsKey(id)) {
                    found.add(Diagnostic.candidate(Severity.ERROR, "offer:unknown-kind", "undeclared kind " + id));
                    refuse = true;
                }
            }
        }
        if (refuse) {
            return kept(live, found);
        }

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
        Draft<D> draft = Objects.requireNonNull(assembler.read(Collections.unmodifiableMap(snapshots)), "draft");
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

        Map<Ref, Part> parts = new TreeMap<>();
        for (Part p : draft.parts()) {
            if (!account()) {
                parts.put(p.ref(), p);
            } else if (!entries.contains(p.ref())) {
                found.add(Diagnostic.of(Severity.ERROR, p.ref(), "assembler:unoffered-part", "not offered"));
                refuse = true;
            } else if (parts.putIfAbsent(p.ref(), p) != null) {
                found.add(Diagnostic.of(Severity.ERROR, p.ref(), "assembler:duplicate-part", "read twice"));
                refuse = true;
            }
        }
        if (account()) {
            for (Ref r : entries) {
                if (!parts.containsKey(r) && !broken.contains(r)) {
                    found.add(Diagnostic.of(Severity.ERROR, r, "assembler:unlisted-entry", "no part, no finding"));
                    refuse = true;
                }
            }
        }
        for (Ref r : broken) {
            Kind k = kinds.get(r.kind());
            if (k != null && k.policy() == Policy.REQUIRED) {
                refuse = true;
            }
        }

        Set<Ref> alive = new TreeSet<>(parts.keySet());
        alive.removeAll(broken);
        if (checkReferences()) {
            boolean changed = true;
            while (changed) {
                changed = false;
                Set<Ref> asRead = new TreeSet<>(alive);
                for (Ref r : new ArrayList<>(alive)) {
                    boolean dangles = false;
                    for (Ref t : parts.get(r).references()) {
                        if (!(transitive() ? alive : asRead).contains(t)) {
                            found.add(Diagnostic.of(Severity.ERROR, r, "reference:dangling", "names " + t));
                            dangles = true;
                        }
                    }
                    if (dangles) {
                        alive.remove(r);
                        changed = transitive();
                        Kind k = kinds.get(r.kind());
                        if (k != null && k.policy() == Policy.REQUIRED) {
                            refuse = true;
                        }
                    }
                }
            }
        }
        if (refuse && honourRefusal()) {
            return kept(live, found);
        }

        TreeMap<Ref, String> manifest = new TreeMap<>();
        for (Ref r : alive) {
            manifest.put(r, fingerprint(parts.get(r)));
        }
        String hash = hash(interpreter, manifest, draft.parts(), offered);
        long number = number(live);
        beforeBuild(number, hash, interpreter, manifest);
        Assembly<G> assembly = Objects.requireNonNull(
                assembler.build(draft.value(), new Resolved(number, hash, interpreter, manifest, report(found))), "assembly");
        found.addAll(assembly.diagnostics());
        boolean errors = assembly.diagnostics().stream().anyMatch(Diagnostic::isError);
        if (assembly instanceof Assembly.Refused<G> || errors) {
            if (!errors) {
                found.add(Diagnostic.candidate(Severity.ERROR, "assembler:refused", "refused"));
            }
            return kept(live, found);
        }
        Generation<G> next = new Generation<>(number, hash, interpreter, manifest, ((Assembly.Built<G>) assembly).value(), report(found));
        publish(next);
        return new Outcome.Published<>(next);
    }

    // ---- for the hash fixtures: the canonical encoding over a listing in whatever order it is given ----

    /** {@link Hashing#generation}'s encoding, but over the entries in the order given: what a hash that forgets to sort does. */
    protected static String listingHash(String interpreter, List<Map.Entry<Ref, String>> listing) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        frame(out, Hashing.DOMAIN);
        frame(out, interpreter);
        u32(out, listing.size());
        for (Map.Entry<Ref, String> e : listing) {
            frame(out, e.getKey().kind());
            frame(out, e.getKey().id());
            frame(out, e.getValue());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(out.toByteArray());
            StringBuilder b = new StringBuilder();
            for (byte x : digest) {
                b.append(Character.forDigit((x >>> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void frame(ByteArrayOutputStream out, String s) {
        byte[] bytes = Hashing.utf8(s);
        u32(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void u32(ByteArrayOutputStream out, int n) {
        out.write(n >>> 24);
        out.write(n >>> 16);
        out.write(n >>> 8);
        out.write(n);
    }
}
