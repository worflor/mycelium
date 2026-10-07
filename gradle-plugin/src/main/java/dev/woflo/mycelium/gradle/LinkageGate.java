package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The linkage gate (docs/decisions/0010): every reference an artifact makes to the game resolves on each version of its
 * claimed range that has name data. A version whose references all resolve is {@code linked}: proven by the artifact's bytes
 * and that version's published names, without a server. A version the range gate records as {@code proven} or {@code pending}
 * keeps that status and is linked as well. A reference that does not resolve on a version of the range fails the build, naming
 * the reference and the version, because the artifact would throw {@code NoSuchMethodError} or {@code NoClassDefFoundError}
 * where it says it runs. A version with no name data stays {@code interpolated}, and the report says why.
 *
 * <p>The gate holds what name data can say: classes, the methods and fields the data numbers and, where the data is the
 * game's own classes, every member and its inheritance. It does not hold what a reference does once it resolves, a Mixin
 * target, a reflective load, or the loader's own API; those are for a run.
 */
public final class LinkageGate {
    private LinkageGate() {
    }

    /** What the gate found ({@code findings} empty: every version that has name data links) and the report to record. */
    public record Result(List<String> findings, JsonObject report) {
    }

    /** A reference that does not resolve, and where the artifact first makes it. */
    record Unresolved(References.Ref ref, String where) {
        String text() {
            return ref.text() + " (first used by " + where + ")";
        }
    }

    /** One version's check against one entry's names. */
    record Check(int classes, int methods, int fields, int unchecked, List<Unresolved> unresolved) {
    }

    /** Reads the game's names for an entry; {@link NameCache.Unavailable} when the data is pinned and not to be had. */
    @FunctionalInterface
    interface Loader {
        Names load(NameData.Entry entry) throws IOException;
    }

    /** At most this many unresolved references per version are written out; the count is exact. */
    static final int LISTED = 12;

    /**
     * Holds {@code found} to {@code names}, the game as {@code entry} pins it: every reference to a class {@code entry}
     * owns must resolve.
     */
    static Check check(References.Found found, NameData.Entry entry, Names names) {
        int classes = 0;
        int methods = 0;
        int fields = 0;
        int unchecked = 0;
        List<Unresolved> unresolved = new ArrayList<>();
        for (Map.Entry<References.Ref, String> e : found.refs().entrySet()) {
            References.Ref ref = e.getKey();
            if (entry.owns().stream().noneMatch(prefix -> ref.owner().startsWith(prefix))) {
                continue;
            }
            Names.Verdict verdict;
            switch (ref.kind()) {
                case 'C' -> {
                    verdict = names.hasClass(ref.owner());
                    classes++;
                }
                case 'M' -> {
                    verdict = names.hasMethod(ref.owners(), ref.name(), ref.descriptor());
                    if (verdict != Names.Verdict.UNKNOWN) {
                        methods++;
                    }
                }
                default -> {
                    verdict = names.hasField(ref.owners(), ref.name(), ref.descriptor());
                    if (verdict != Names.Verdict.UNKNOWN) {
                        fields++;
                    }
                }
            }
            if (verdict == Names.Verdict.UNKNOWN) {
                unchecked++;
            } else if (verdict == Names.Verdict.ABSENT) {
                unresolved.add(new Unresolved(ref, e.getValue()));
            }
        }
        return new Check(classes, methods, fields, unchecked, unresolved);
    }

    /**
     * The range report's versions, each linked where the names say so.
     *
     * @param range    {@code myceliumRange}'s report ({@link RangeGate}); its {@code versions} are the versions in the claim
     * @param artifact the claim
     * @param namespace the naming of the game the artifact references, or {@code null}: what the era table says its loader runs under
     * @param found    the artifact's references
     * @param loadNames reads the game's names for an entry
     * @param runtimeLinked versions already linked by something the build ran against that version's own runtime, with what
     *                     (a capsule's final runtime view): they are linked without name data
     * @param offline  whether a version whose data cannot be had is recorded and left alone (offline) or fails the build
     */
    static Result evaluate(JsonObject range, ArtifactLedger.Artifact artifact, String namespace, Eras eras, NameData data, References.Found found,
                           Loader loadNames, Map<String, String> runtimeLinked, boolean offline) {
        List<String> findings = new ArrayList<>();
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        report.addProperty("id", artifact.id());
        report.addProperty("loader", artifact.loader());
        JsonObject refs = new JsonObject();
        refs.addProperty("classFiles", found.classFiles());
        refs.addProperty("references", found.refs().size());
        refs.addProperty("excludedClasses", found.excluded());
        report.add("artifact", refs);

        JsonArray versions = new JsonArray();
        int proven = 0;
        int pending = 0;
        int linked = 0;
        int interpolated = 0;
        for (JsonElement e : range.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            String version = v.get("version").getAsString();
            String run = v.get("status").getAsString();
            JsonObject link = new JsonObject();
            boolean ok = false;
            String names = namespace != null ? namespace : eras.of(version).names().runtimeFor(artifact.loader(), version);
            NameData.Entry entry = names == null ? null : data.entry(names, version);
            if (runtimeLinked.containsKey(version)) {
                link.addProperty("status", "linked");
                link.addProperty("by", runtimeLinked.get(version));
                ok = true;
            } else if (names == null) {
                link.addProperty("status", "none");
                link.addProperty("why", "the era table names no runtime names for " + artifact.loader() + " on " + version);
            } else if (entry == null) {
                link.addProperty("status", "none");
                link.addProperty("namespace", names);
                link.addProperty("why", "no name data is pinned for " + names + " " + version + " (eras/names.json)");
            } else {
                link.addProperty("namespace", names);
                try {
                    Names resolved = loadNames.load(entry);
                    Check c = check(found, entry, resolved);
                    link.addProperty("source", resolved.describe());
                    JsonArray inputs = new JsonArray();
                    for (NameData.Input i : entry.inputs()) {
                        JsonObject io = new JsonObject();
                        io.addProperty("role", i.role());
                        io.addProperty("sha256", i.sha256());
                        inputs.add(io);
                    }
                    link.add("inputs", inputs);
                    JsonObject checked = new JsonObject();
                    checked.addProperty("classes", c.classes());
                    checked.addProperty("methods", c.methods());
                    checked.addProperty("fields", c.fields());
                    checked.addProperty("notNumbered", c.unchecked());
                    link.add("checked", checked);
                    if (c.unresolved().isEmpty()) {
                        link.addProperty("status", "linked");
                        ok = true;
                    } else {
                        link.addProperty("status", "broken");
                        JsonArray missing = new JsonArray();
                        c.unresolved().stream().limit(LISTED).forEach(u -> missing.add(u.text()));
                        link.add("unresolved", missing);
                        link.addProperty("unresolvedCount", c.unresolved().size());
                        StringBuilder f = new StringBuilder(version + ": " + c.unresolved().size() + " reference"
                                + (c.unresolved().size() == 1 ? "" : "s") + " do" + (c.unresolved().size() == 1 ? "es" : "") + " not resolve in "
                                + names + " " + version + " (the artifact claims it runs there):");
                        c.unresolved().stream().limit(LISTED).forEach(u -> f.append("\n      ").append(u.text()));
                        if (c.unresolved().size() > LISTED) {
                            f.append("\n      ... and ").append(c.unresolved().size() - LISTED).append(" more");
                        }
                        findings.add(f.toString());
                    }
                } catch (NameCache.Unavailable u) {
                    link.addProperty("status", "unavailable");
                    link.addProperty("why", u.getMessage());
                    if (!offline) {
                        findings.add(version + ": its " + names + " name data is pinned (eras/names.json) and could not be had: "
                                + u.getMessage() + ". Build online once to cache it, or build offline (the version then stays "
                                + (run.equals("interpolated") ? "interpolated" : run) + ").");
                    }
                } catch (IOException | RuntimeException x) {
                    link.addProperty("status", "unavailable");
                    link.addProperty("why", String.valueOf(x.getMessage()));
                    findings.add(version + ": its " + names + " name data could not be read: " + x.getMessage());
                }
            }
            JsonObject out = new JsonObject();
            out.addProperty("version", version);
            String status = run;
            if (run.equals("interpolated") && ok) {
                status = "linked";
            }
            out.addProperty("status", status);
            out.addProperty("run", run.equals("interpolated") ? "none" : run);
            out.add("linkage", link);
            versions.add(out);
            switch (status) {
                case "proven" -> proven++;
                case "pending" -> pending++;
                case "linked" -> linked++;
                default -> interpolated++;
            }
        }
        report.add("versions", versions);
        JsonObject counts = new JsonObject();
        counts.addProperty("proven", proven);
        counts.addProperty("pending", pending);
        counts.addProperty("linked", linked);
        counts.addProperty("interpolated", interpolated);
        report.add("counts", counts);
        JsonArray f = new JsonArray();
        findings.forEach(f::add);
        report.add("findings", f);
        return new Result(findings, report);
    }

    /** The versions of a range report with their {@code run} status, for a caller that wants to know what ran where. */
    static Map<String, String> runs(JsonObject range) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonElement e : range.getAsJsonArray("versions")) {
            out.put(e.getAsJsonObject().get("version").getAsString(), e.getAsJsonObject().get("status").getAsString());
        }
        return out;
    }
}
