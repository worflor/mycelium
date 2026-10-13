package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The range gate (docs/decisions/0009): an artifact says which Minecraft versions it runs on, and the gate holds the claim
 * to what the era table and the loader's own metadata say, on facts alone.
 *
 * <ul>
 *   <li><b>Real and available.</b> Both ends of the range are versions the launcher names, the range runs forwards, and on
 *       every version in it some platform the artifact claims exists ({@code since} in the era table).</li>
 *   <li><b>Proofs.</b> Every proof version is real, inside the range, on a platform that exists there. A range has a
 *       keystone, a version servers really run.</li>
 *   <li><b>Walls.</b> Each wall that splits the artifact's loader (or family) inside the range divides it into segments, and
 *       every segment holds a proof: a wall in a range needs a proof on each side. A wall at the range's first version
 *       divides nothing.</li>
 *   <li><b>The loader's metadata.</b> What the built artifact declares to the loader ({@link CompatMetadata}) accepts every
 *       version in the range: the loader would otherwise refuse the artifact where it claims to run.</li>
 * </ul>
 * Every version in the range is then recorded as {@code proven} (a proof version not marked pending), {@code pending} (a
 * proof declared and not yet run) or {@code interpolated} (inside the range, with no proof of its own: a claim). The gate
 * does not link the artifact against any version's game; {@code myceliumLinkage} ({@link LinkageGate}) does, on the finished
 * artifact, and the report says which of the two a build ran.
 */
public final class RangeGate {
    private RangeGate() {
    }

    /** What the gate found ({@code findings} empty: the claim holds as far as the gate looks) and the report to record. */
    public record Result(List<String> findings, JsonObject report, Map<String, String> ids) {
        public Result(List<String> findings, JsonObject report) {
            this(findings, report, Map.of());
        }

        /** The check ({@link Checks}) a finding belongs to: R0 the claim is real, R1 a keystone, R2 each side of a wall, R3 the metadata. */
        public String checkOf(String finding) {
            return ids.getOrDefault(finding, "R0");
        }
    }

    /** Findings, each tagged with the check it belongs to ({@code R0} when untagged). */
    static final class Found extends ArrayList<String> {
        final Map<String, String> ids = new LinkedHashMap<>();

        boolean add(String id, String message) {
            ids.put(message, id);
            return super.add(message);
        }
    }

    /**
     * Checks {@code a}. {@code real} is {@code eras/versions.json}; {@code found} what the built artifact declares to its
     * loader ({@code null}: its resources were not read, and the report says the metadata was not checked).
     */
    public static Result check(ArtifactLedger.Artifact a, Eras eras, Loaders loaders, List<String> real, CompatMetadata.Found found) {
        return check(a, eras, loaders, real, found, false);
    }

    /** As above; {@code linkageDeclared} says the artifact also runs {@code myceliumLinkage}, which the report points to. */
    public static Result check(ArtifactLedger.Artifact a, Eras eras, Loaders loaders, List<String> real, CompatMetadata.Found found,
                               boolean linkageDeclared) {
        Found findings = new Found();
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("id", a.id());
        report.addProperty("loader", a.loader());

        Loaders.Loader loader = loaders.loaders().get(a.loader());
        if (loader == null) {
            findings.add("the loader '" + a.loader() + "' is not one eras/loaders.json knows (" + new java.util.TreeSet<>(loaders.loaders().keySet()) + ")");
            return done(report, findings, false);
        }
        List<String> platforms = a.platforms().isEmpty() ? List.of(a.loader()) : a.platforms();
        for (String p : platforms) {
            Loaders.Loader l = loaders.loaders().get(p);
            if (l == null) {
                findings.add("the platform '" + p + "' is not a loader eras/loaders.json knows");
            } else if (!l.family().equals(loader.family())) {
                findings.add("the platform '" + p + "' is a " + l.family() + " and the loader '" + a.loader() + "' a " + loader.family()
                        + ": one artifact runs on one family");
            }
        }
        JsonArray platformList = new JsonArray();
        platforms.forEach(platformList::add);
        report.add("platforms", platformList);
        JsonObject range = new JsonObject();
        range.addProperty("from", a.from());
        range.addProperty("to", a.to());
        report.add("range", range);

        Set<String> realSet = new HashSet<>(real);
        boolean fromReal = realSet.contains(a.from()) && !Eras.isLine(a.from());
        boolean toReal = Eras.isLine(a.to())
                ? real.stream().anyMatch(v -> Eras.readable(v) && Eras.atLeast(v, a.to()) && Eras.atMost(v, a.to()))
                : realSet.contains(a.to());
        if (!fromReal) {
            findings.add("the range begins at '" + a.from() + "', which is not a version the launcher names (eras/versions.json)");
        }
        if (!toReal) {
            findings.add("the range ends at '" + a.to() + "', which is not a version, or a line with a version, the launcher names (eras/versions.json)");
        }
        if (fromReal && toReal && !Eras.atMost(a.from(), a.to())) {
            findings.add("the range runs backwards: from " + a.from() + " to " + a.to());
            toReal = false;
        }
        if (!fromReal || !toReal) {
            return done(report, findings, false);
        }

        List<String> versions = new ArrayList<>();
        for (String v : real) {
            if (Eras.readable(v) && Eras.atLeast(v, a.from()) && Eras.atMost(v, a.to())) {
                versions.add(v);
            }
        }
        versions.sort(Eras::compare);

        availability(a, platforms, eras, versions, findings);
        Map<String, List<ArtifactLedger.Proof>> byVersion = proofs(a, loader, loaders, eras, realSet, findings);
        List<String[]> walls = walls(a, loader, platforms, loaders, eras, versions);
        segments(a, versions, walls, byVersion, findings);
        for (String v : a.pins().keySet()) {
            if (!v.equals("*") && !byVersion.containsKey(v)) {
                findings.add("pins are given for '" + v + "', which is not a proof version of the artifact");
            }
        }
        for (String p : a.pending()) {
            if (!byVersion.containsKey(p)) {
                findings.add("'" + p + "' is marked pending but is neither a keystone nor a flex");
            }
        }

        Set<String> pending = new HashSet<>(a.pending());
        JsonArray versionList = new JsonArray();
        int proven = 0;
        int waiting = 0;
        int interpolated = 0;
        for (String v : versions) {
            JsonObject one = new JsonObject();
            one.addProperty("version", v);
            List<ArtifactLedger.Proof> ps = byVersion.get(v);
            if (ps == null) {
                one.addProperty("status", "interpolated");
                interpolated++;
            } else {
                one.addProperty("status", pending.contains(v) ? "pending" : "proven");
                one.addProperty("proof", a.keystones().stream().anyMatch(k -> k.mc().equals(v)) ? "keystone" : "flex");
                Set<String> on = new LinkedHashSet<>();
                ps.forEach(p -> on.addAll(p.on().isEmpty() ? List.of(a.loader()) : p.on()));
                JsonArray onList = new JsonArray();
                on.forEach(onList::add);
                one.add("on", onList);
                if (pending.contains(v)) {
                    waiting++;
                } else {
                    proven++;
                }
            }
            versionList.add(one);
        }
        report.add("versions", versionList);
        JsonObject counts = new JsonObject();
        counts.addProperty("proven", proven);
        counts.addProperty("pending", waiting);
        counts.addProperty("interpolated", interpolated);
        report.add("counts", counts);
        JsonArray wallList = new JsonArray();
        for (String[] w : walls) {
            JsonObject one = new JsonObject();
            one.addProperty("at", w[0]);
            one.addProperty("what", w[1]);
            wallList.add(one);
        }
        report.add("walls", wallList);

        report.add("metadata", metadata(a, loader, versions, real, found, findings));
        JsonObject linkage = new JsonObject();
        linkage.addProperty("checked", false);
        if (linkageDeclared) {
            linkage.addProperty("deferred", true);
            linkage.addProperty("why", "linkage reads the finished artifact, which this manifest is sealed into, so it runs after it: "
                    + "build/mycelium/linkage/linkage.json (myceliumLinkage) names that archive by SHA-256 and records each version as "
                    + "proven, pending, linked or interpolated (docs/decisions/0010)");
        } else {
            linkage.addProperty("why", "the gate holds the claim to the era table, the proofs and the loader's metadata; the artifact "
                    + "declares no linkage (mycelium { range { linkage { ... } } }), so no version is held to the game's published names"
                    + " (docs/decisions/0010)");
        }
        report.add("linkage", linkage);
        return done(report, findings, true);
    }

    private static Result done(JsonObject report, Found findings, boolean enumerated) {
        report.addProperty("checked", enumerated);
        JsonArray f = new JsonArray();
        findings.forEach(f::add);
        report.add("findings", f);
        return new Result(findings, report, findings.ids);
    }

    // --- availability ----------------------------------------------------------------------------------------------

    private static void availability(ArtifactLedger.Artifact a, List<String> platforms, Eras eras, List<String> versions,
                                     Found findings) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (String v : versions) {
            Eras.Era era;
            try {
                era = eras.of(v);
            } catch (IllegalArgumentException e) {
                findings.add("no era covers " + v + ", which the range includes (eras/minecraft.json)");
                continue;
            }
            if (platforms.stream().noneMatch(p -> era.offers(p, v))) {
                List<String> why = new ArrayList<>();
                for (String p : platforms) {
                    if (!era.loaders().contains(p)) {
                        why.add(p + " is not in era '" + era.id() + "'");
                    } else {
                        why.add(p + " begins on " + era.since().get(p));
                    }
                }
                byReason.computeIfAbsent(String.join("; ", why), k -> new ArrayList<>()).add(v);
            }
        }
        byReason.forEach((why, vs) -> findings.add("the range includes " + list(vs) + ", which no platform the artifact claims offers ("
                + why + "): a range claims only versions its loader exists on"));
    }

    // --- proofs ----------------------------------------------------------------------------------------------------

    /** The proofs by version, each checked; a proof that is not usable is a finding and is left out. */
    private static Map<String, List<ArtifactLedger.Proof>> proofs(ArtifactLedger.Artifact a, Loaders.Loader loader, Loaders loaders,
                                                                  Eras eras, Set<String> real, Found findings) {
        Map<String, List<ArtifactLedger.Proof>> out = new LinkedHashMap<>();
        if (a.keystones().isEmpty()) {
            findings.add("R1", "the artifact declares no keystone: a range is claimed from a version servers really run (its versions stay interpolated)");
        }
        Set<String> claimed = new HashSet<>(a.platforms().isEmpty() ? List.of(a.loader()) : a.platforms());
        Set<String> seen = new HashSet<>();
        for (boolean keystone : new boolean[]{true, false}) {
            String kind = keystone ? "keystone" : "flex";
            for (ArtifactLedger.Proof p : keystone ? a.keystones() : a.flex()) {
                if (!real.contains(p.mc()) || Eras.isLine(p.mc()) || !Eras.readable(p.mc())) {
                    findings.add("the " + kind + " '" + p.mc() + "' is not a version the launcher names (eras/versions.json)");
                    continue;
                }
                if (!Eras.atLeast(p.mc(), a.from()) || !Eras.atMost(p.mc(), a.to())) {
                    findings.add("the " + kind + " '" + p.mc() + "' is outside the range " + a.from() + " to " + a.to()
                            + ": a proof outside the range proves nothing the range claims");
                    continue;
                }
                if (!seen.add(kind + " " + p.mc() + " " + p.on())) {
                    findings.add("the " + kind + " '" + p.mc() + "' is declared twice");
                    continue;
                }
                boolean usable = true;
                Eras.Era era = eras.of(p.mc());
                for (String platform : p.on().isEmpty() ? List.of(a.loader()) : p.on()) {
                    Loaders.Loader l = loaders.loaders().get(platform);
                    if (l == null || !l.family().equals(loader.family())) {
                        findings.add("the " + kind + " '" + p.mc() + "' runs on '" + platform + "', which is not a " + loader.family()
                                + " loader eras/loaders.json knows");
                        usable = false;
                    } else if (!claimed.contains(platform)) {
                        findings.add("the " + kind + " '" + p.mc() + "' runs on '" + platform + "', which the artifact does not claim ("
                                + new java.util.TreeSet<>(claimed) + ")");
                        usable = false;
                    } else if (!era.offers(platform, p.mc())) {
                        findings.add("the " + kind + " '" + p.mc() + "' runs on '" + platform + "', which does not exist on it"
                                + (era.since().containsKey(platform) ? " (it begins on " + era.since().get(platform) + ")" : ""));
                        usable = false;
                    }
                }
                if (usable) {
                    out.computeIfAbsent(p.mc(), k -> new ArrayList<>()).add(p);
                }
            }
        }
        return out;
    }

    // --- walls -----------------------------------------------------------------------------------------------------

    /** The walls inside the range that split the artifact, ascending, each as {at, what}; walls at one version are one. */
    private static List<String[]> walls(ArtifactLedger.Artifact a, Loaders.Loader loader, List<String> platforms, Loaders loaders,
                                        Eras eras, List<String> versions) {
        Map<String, List<String>> at = new TreeMap<>(Eras::compare);
        Set<String> inRange = new HashSet<>(versions);
        Set<String> seenEras = new HashSet<>();
        for (String v : versions) {
            Eras.Era era = eras.of(v);
            if (!seenEras.add(era.id())) {
                continue;
            }
            for (Eras.Wall w : era.walls()) {
                boolean splits = w.splits(a.loader(), loader.family())
                        || platforms.stream().anyMatch(p -> w.splits(p, loaders.loaders().get(p) == null ? "" : loaders.loaders().get(p).family()));
                if (splits && inRange.contains(w.at()) && Eras.compare(w.at(), a.from()) > 0) {
                    at.computeIfAbsent(w.at(), k -> new ArrayList<>()).add(w.what());
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        at.forEach((k, v) -> out.add(new String[]{k, String.join("; ", v)}));
        return out;
    }

    private static void segments(ArtifactLedger.Artifact a, List<String> versions, List<String[]> walls,
                                 Map<String, List<ArtifactLedger.Proof>> proofs, Found findings) {
        if (walls.isEmpty()) {
            return; // one segment: the keystone requirement already asks it for a proof
        }
        List<String> bounds = new ArrayList<>();
        bounds.add(a.from());
        walls.forEach(w -> bounds.add(w[0]));
        for (int i = 0; i < bounds.size(); i++) {
            String start = bounds.get(i);
            String end = i + 1 < bounds.size() ? bounds.get(i + 1) : null;
            boolean held = proofs.keySet().stream().anyMatch(p -> Eras.compare(p, start) >= 0 && (end == null || Eras.compare(p, end) < 0));
            if (held) {
                continue;
            }
            String last = start;
            for (String v : versions) {
                if (Eras.compare(v, start) >= 0 && (end == null || Eras.compare(v, end) < 0)) {
                    last = v;
                }
            }
            String span = start.equals(last) ? start : start + " to " + last;
            String where;
            if (i > 0 && end != null) {
                where = "between the walls at " + start + " (" + walls.get(i - 1)[1] + ") and " + end + " (" + walls.get(i)[1] + ")";
            } else if (i > 0) {
                where = "the far side of the wall at " + start + " (" + walls.get(i - 1)[1] + ")";
            } else {
                where = "the near side of the wall at " + end + " (" + walls.get(i)[1] + ")";
            }
            findings.add("R2", "no keystone or flex lies in " + span + ", " + where + ": a wall inside a range needs a proof on each side");
        }
    }

    // --- the loader's metadata -------------------------------------------------------------------------------------

    private static JsonObject metadata(ArtifactLedger.Artifact a, Loaders.Loader loader, List<String> versions, List<String> real,
                                       CompatMetadata.Found found, Found findings) {
        JsonObject m = new JsonObject();
        Loaders.Compat c = loader.compat();
        if (c == null) {
            return unchecked(m, "the loader '" + a.loader() + "' declares no compatibility the build can read (eras/loaders.json)");
        }
        if (c.from() != null && Eras.compare(versions.get(versions.size() - 1), c.from()) < 0) {
            return unchecked(m, c.before());
        }
        if (c.from() != null && Eras.compare(versions.get(0), c.from()) < 0) {
            findings.add("R3", "the range reaches back past " + c.from() + ", where the loader declares compatibility in a file, into versions where "
                    + "it does not (" + c.before() + "): one artifact is on one side of it");
            return unchecked(m, "the range straddles " + c.from());
        }
        if (found == null) {
            return unchecked(m, "the artifact's built resources were not read");
        }
        m.addProperty("checked", true);
        m.addProperty("syntax", c.syntax());
        if (found.file() == null) {
            findings.add("R3", "the artifact carries none of " + c.files() + ", where the loader reads the Minecraft versions it accepts");
            return m;
        }
        m.addProperty("file", found.file());
        if (found.value() == null) {
            if (c.syntax().equals("api-version")) {
                // a plugin that names no API version is a legacy one, which every server accepts
                List<String> modern = new ArrayList<>();
                versions.stream().filter(v -> Eras.compare(v, "1.13") >= 0).forEach(modern::add);
                if (!modern.isEmpty()) {
                    findings.add("R3", found.file() + " declares no " + c.key() + ", which servers read as a legacy plugin; the range includes "
                            + list(modern) + ", where a plugin that does not declare its API version is treated as one written before it");
                }
                m.addProperty("value", "legacy (none declared)");
            } else {
                findings.add("R3", found.file() + " declares no Minecraft version, so the loader would accept the artifact on any: the range is not what it enforces");
            }
            return m;
        }
        m.addProperty("value", found.value());
        Predicate<String> accepts;
        try {
            accepts = CompatExpr.parse(c.syntax(), found.value());
        } catch (IllegalArgumentException e) {
            findings.add("R3", found.file() + " declares '" + found.value() + "', which is not a " + c.syntax() + " range: " + e.getMessage());
            return m;
        }
        List<String> refused = new ArrayList<>();
        versions.stream().filter(accepts.negate()).forEach(refused::add);
        if (!refused.isEmpty()) {
            findings.add("R3", found.file() + " accepts '" + found.value() + "', which refuses " + list(refused)
                    + " in the range: the loader would not run the artifact where it claims to");
        }
        Set<String> inRange = new HashSet<>(versions);
        List<String> beyond = new ArrayList<>();
        real.stream().filter(Eras::readable).filter(v -> !inRange.contains(v) && accepts.test(v)).sorted(Eras::compare).forEach(beyond::add);
        JsonArray beyondList = new JsonArray();
        beyond.forEach(beyondList::add);
        m.add("acceptsBeyondRange", beyondList);
        return m;
    }

    private static JsonObject unchecked(JsonObject m, String why) {
        m.addProperty("checked", false);
        m.addProperty("why", why);
        return m;
    }

    /** {@code vs} for a message: all of them when few, the ends and the count when many. */
    private static String list(List<String> vs) {
        if (vs.size() <= 4) {
            return String.join(", ", vs);
        }
        return vs.get(0) + ", " + vs.get(1) + " ... " + vs.get(vs.size() - 1) + " (" + vs.size() + " versions)";
    }
}
