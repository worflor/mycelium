package mycelium.data_generation.v1.harness;

import mycelium.data_catalog.v1.model.Rejection;
import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Assembly;
import mycelium.data_generation.v1.model.Diagnostic;
import mycelium.data_generation.v1.model.Draft;
import mycelium.data_generation.v1.model.Kind;
import mycelium.data_generation.v1.model.Part;
import mycelium.data_generation.v1.model.Ref;
import mycelium.data_generation.v1.model.Resolved;
import mycelium.data_generation.v1.model.Severity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The world the harness offers: three kinds shaped like a mod's rules and the data that names them ({@code rules},
 * required; {@code structures} and {@code bindings}, optional), records that carry their own references, and an assembler
 * that can be told to misbehave the ways real assemblers do.
 */
final class GenerationWorld {
    static final String RULES = "rules";
    static final String STRUCTURES = "structures";
    static final String BINDINGS = "bindings";

    private GenerationWorld() {
    }

    static Ref ref(String kind, String id) {
        return new Ref(kind, id);
    }

    static Rec rec(String payload, Ref... refs) {
        return new Rec(payload, new TreeSet<>(List.of(refs)), false, false);
    }

    /** The canonical content of a record: its payload, then each reference on a line of its own, in ref order. */
    static String content(Rec rec) {
        StringBuilder b = new StringBuilder(rec.payload());
        for (Ref r : rec.refs()) {
            b.append('\n').append(r.kind()).append('/').append(r.id());
        }
        return b.toString();
    }

    /** One data file's parsed body: a payload, the entries it names, and whether it reads at all or reads oddly. */
    record Rec(String payload, SortedSet<Ref> refs, boolean bad, boolean warn) {
        Rec {
            refs = Collections.unmodifiableSortedSet(new TreeSet<>(refs));
        }

        Rec asBad() {
            return new Rec(payload, refs, true, warn);
        }

        Rec asOdd() {
            return new Rec(payload, refs, bad, true);
        }
    }

    /** The generation's value: the number and hash it was built under, and every published entry's payload. */
    record World(long number, String hash, SortedMap<Ref, String> payloads) {
        World {
            payloads = Collections.unmodifiableSortedMap(new TreeMap<>(payloads));
        }
    }

    /** One offer: a snapshot per kind, in a chosen map order; kinds left out of the order are not offered. */
    static final class Offer {
        private final Map<String, TreeMap<String, Rec>> entries = new LinkedHashMap<>();
        private final Map<String, List<Rejection>> rejected = new LinkedHashMap<>();
        private List<String> order = new ArrayList<>(List.of(RULES, STRUCTURES, BINDINGS));
        private final List<String> extra = new ArrayList<>();

        Offer() {
            for (String k : List.of(RULES, STRUCTURES, BINDINGS)) {
                entries.put(k, new TreeMap<>());
                rejected.put(k, new ArrayList<>());
            }
        }

        Offer put(String kind, String id, Rec rec) {
            entries.get(kind).put(id, rec);
            return this;
        }

        Offer remove(String kind, String id) {
            entries.get(kind).remove(id);
            return this;
        }

        Offer reject(String kind, String id, String reason) {
            rejected.get(kind).add(new Rejection(id, reason));
            return this;
        }

        Offer order(String... kinds) {
            order = List.of(kinds);
            return this;
        }

        Offer extra(String kind) {
            extra.add(kind);
            return this;
        }

        Map<String, Snapshot<?>> snapshots() {
            Map<String, Snapshot<?>> out = new LinkedHashMap<>();
            for (String k : order) {
                out.put(k, new Snapshot<>(1, entries.get(k), rejected.get(k)));
            }
            for (String k : extra) {
                out.put(k, Snapshot.empty());
            }
            return out;
        }

        Map<Ref, Rec> recs() {
            Map<Ref, Rec> out = new TreeMap<>();
            for (String k : order) {
                entries.get(k).forEach((id, rec) -> out.put(new Ref(k, id), rec));
            }
            return out;
        }

        /** Every entry and every rejected file of the offered kinds: what the generation must account for. */
        Set<Ref> offeredRefs() {
            Set<Ref> out = new TreeSet<>(recs().keySet());
            for (String k : order) {
                for (Rejection r : rejected.get(k)) {
                    out.add(new Ref(k, r.id()));
                }
            }
            return out;
        }
    }

    /**
     * The harness's assembler. By default it reads every record into a part (a bad one into an ERROR finding and no
     * part), warns on an odd one, and refuses a candidate with no rules at all. Its switches make it misbehave.
     */
    static final class TestAssembler implements Assembler<Map<Ref, Rec>, World> {
        String interpreter = "1";
        boolean reverse;
        Ref omit;
        Ref invent;
        boolean throwInRead;
        boolean throwInBuild;
        String candidateError;
        Runnable probe = () -> {
        };

        @Override
        public String interpreter() {
            return interpreter;
        }

        @Override
        public List<Kind> kinds() {
            return List.of(Kind.required(RULES), Kind.optional(STRUCTURES), Kind.optional(BINDINGS));
        }

        @Override
        public Draft<Map<Ref, Rec>> read(Map<String, Snapshot<?>> offered) {
            probe.run();
            if (throwInRead) {
                throw new IllegalStateException("the assembler broke while reading");
            }
            Map<Ref, Rec> recs = new TreeMap<>();
            List<Part> parts = new ArrayList<>();
            List<Diagnostic> found = new ArrayList<>();
            for (String kind : new TreeSet<>(offered.keySet())) {
                for (Map.Entry<String, ?> e : offered.get(kind).entries().entrySet()) {
                    Ref ref = new Ref(kind, e.getKey());
                    Rec rec = (Rec) e.getValue();
                    if (ref.equals(omit)) {
                        continue;
                    }
                    recs.put(ref, rec);
                    if (rec.bad()) {
                        found.add(Diagnostic.of(Severity.ERROR, ref, "test:bad", "this entry does not read"));
                        continue;
                    }
                    parts.add(Part.of(ref, content(rec), rec.refs()));
                    if (rec.warn()) {
                        found.add(Diagnostic.of(Severity.WARNING, ref, "test:warn", "this entry reads, oddly"));
                    }
                }
            }
            if (invent != null) {
                parts.add(Part.of(invent, "invented", Set.of()));
            }
            if (candidateError != null) {
                found.add(Diagnostic.candidate(Severity.ERROR, "test:candidate", candidateError));
            }
            if (reverse) {
                Collections.reverse(parts);
            }
            return new Draft<>(recs, parts, found);
        }

        @Override
        public Assembly<World> build(Map<Ref, Rec> recs, Resolved resolved) {
            probe.run();
            if (throwInBuild) {
                throw new IllegalStateException("the assembler broke while building");
            }
            if (resolved.manifest().keySet().stream().noneMatch(r -> r.kind().equals(RULES))) {
                return new Assembly.Refused<>(List.of(Diagnostic.candidate(Severity.ERROR, "test:no-rules", "a generation needs its rules")));
            }
            SortedMap<Ref, String> payloads = new TreeMap<>();
            for (Ref r : resolved.manifest().keySet()) {
                Rec rec = recs.get(r);
                payloads.put(r, rec == null ? "?" : rec.payload());
            }
            return new Assembly.Built<>(new World(resolved.number(), resolved.hash(), payloads), List.of());
        }
    }
}
