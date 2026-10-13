package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The repository check's half for laws: every label a contract's harness asserts is held to a broken implementation, or the
 * package says why not. A law (a label such as {@code C1:standstill}) that no matrix entry targets can be broken by an
 * implementation and no harness would notice, so the matrix could be green while the law is decoration. The rule
 * (CONTRACT-PACKAGE.md, rule 5): each asserted {@code Law:label} has at least one rejection-matrix entry whose intended label it
 * is, or an allowance in the package's {@code unmutated.json} that says why none is worth writing. An allowance is a reasoned
 * statement, never a blanket: a label that restates another, a law only the real game can break, a guard no realistic mistake
 * reaches. The check counts both, and fails an allowance that has become stale (its label is now mutated, or no longer asserted).
 *
 * <p>The packages that predate the rule are listed in {@code contracts/unaudited.json}, a ledger that can only shrink: a package in
 * it may have unmutated labels without allowances, one not in it may not, and an entry whose package has no unmutated label left
 * (or has an {@code unmutated.json}) is stale. The check reports the size of that debt, so it stays in view.
 */
final class LawCoverage {
    private LawCoverage() {
    }

    /** A label as a harness asserts it: a law id and a name, {@code C1:standstill}. */
    static final Pattern LABEL = Pattern.compile("[A-Z]{1,3}[0-9]+[a-z]?:[a-z0-9][A-Za-z0-9._\\-]*");
    /** A string literal that is exactly a label: found by its shape, so an escaped quote elsewhere in a file cannot mis-pair the rest. */
    private static final Pattern LITERAL = Pattern.compile("\"(" + LABEL.pattern() + ")\"");

    static final String ALLOWANCES = "unmutated.json";
    static final String LEDGER = "contracts/unaudited.json";

    /** What a package's harness asserts and what its matrix targets. */
    record Package(Set<String> asserted, Set<String> intended) {
        /** An asserted label no entry targets. A label a harness builds with a suffix (ends in {@code -}) is targeted by any entry that extends it. */
        Set<String> unmutated() {
            Set<String> out = new TreeSet<>();
            for (String label : asserted) {
                boolean prefix = label.endsWith("-") || label.endsWith(".") || label.endsWith("_");
                boolean hit = intended.stream().anyMatch(i -> prefix ? i.startsWith(label) : i.equals(label));
                if (!hit) {
                    out.add(label);
                }
            }
            return out;
        }
    }

    /**
     * Every package's labels. {@code intended} is each contract's matrix as its harness ran it (the verify report); where it has no
     * entry for a contract, the entries are read from the package's {@code Run.java}, which is every package's matrix.
     */
    static Map<String, Package> read(Path root, ContractRegistry registry, Map<String, Set<String>> intended) throws IOException {
        Map<String, Package> out = new LinkedHashMap<>();
        for (ContractRegistry.Entry e : registry.all()) {
            Path harness = root.resolve("contracts").resolve(e.path()).resolve("src").resolve(e.pkg().replace('.', '/')).resolve("harness");
            if (!Files.isDirectory(harness)) {
                continue;
            }
            Set<String> asserted = new TreeSet<>();
            Set<String> viaRun = new TreeSet<>();
            try (Stream<Path> list = Files.list(harness)) {
                for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".java")).sorted()::iterator) {
                    Set<String> labels = labelsIn(Files.readString(p, StandardCharsets.UTF_8));
                    if (p.getFileName().toString().equals("Run.java")) {
                        viaRun.addAll(labels);
                    } else {
                        asserted.addAll(labels);
                    }
                }
            }
            out.put(e.id(), new Package(asserted, intended != null && intended.containsKey(e.id()) ? intended.get(e.id()) : viaRun));
        }
        return out;
    }

    static Set<String> labelsIn(String source) {
        Set<String> out = new TreeSet<>();
        Matcher m = LITERAL.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** A package's allowances: label to reason. Strictly read; a missing file is an empty map. */
    static Map<String, String> allowances(Path file, String where, RepositoryCheck.Report report) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        JsonObject o;
        try {
            o = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        } catch (RuntimeException e) {
            report.find("laws", where + ": not JSON: " + e.getMessage());
            return out;
        }
        for (String key : o.keySet()) {
            if (!key.equals("schema") && !key.equals("unmutated")) {
                report.find("laws", where + ": unknown field '" + key + "' (allowed: schema, unmutated)");
            }
        }
        if (!o.has("unmutated") || !o.get("unmutated").isJsonObject()) {
            report.find("laws", where + ": \"unmutated\" is an object of label to reason");
            return out;
        }
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("unmutated").entrySet()) {
            String reason = e.getValue().isJsonPrimitive() ? e.getValue().getAsString().strip() : "";
            if (reason.length() < 24) {
                report.find("laws", where + ": the allowance for " + e.getKey() + " has no reason worth the name (say what restates it, or"
                        + " what only the game can break): '" + reason + "'");
            }
            out.put(e.getKey(), reason);
        }
        return out;
    }

    static void run(Path root, ContractRegistry registry, Map<String, Set<String>> intended, RepositoryCheck.Report report) throws IOException {
        Map<String, Package> packages = read(root, registry, intended);
        Set<String> unaudited = new LinkedHashSet<>();
        Path ledger = root.resolve(LEDGER);
        if (Files.isRegularFile(ledger)) {
            try {
                JsonObject o = new Gson().fromJson(Files.readString(ledger, StandardCharsets.UTF_8), JsonObject.class);
                for (String key : o.keySet()) {
                    if (!Set.of("schema", "why", "contracts").contains(key)) {
                        report.find("laws", LEDGER + ": unknown field '" + key + "' (allowed: schema, why, contracts)");
                    }
                }
                o.getAsJsonArray("contracts").forEach(e -> unaudited.add(e.getAsString()));
            } catch (RuntimeException e) {
                report.find("laws", LEDGER + ": unreadable: " + e);
            }
        }
        int labels = 0;
        int mutated = 0;
        int allowed = 0;
        int audited = 0;
        int debt = 0;
        int debtPackages = 0;
        for (Map.Entry<String, Package> entry : packages.entrySet()) {
            String id = entry.getKey();
            ContractRegistry.Entry e = registry.require(id);
            Package p = entry.getValue();
            Set<String> unmutated = p.unmutated();
            labels += p.asserted().size();
            mutated += p.asserted().size() - unmutated.size();
            Path file = root.resolve("contracts").resolve(e.path()).resolve(ALLOWANCES);
            String where = "contracts/" + e.path() + "/" + ALLOWANCES;
            boolean hasFile = Files.isRegularFile(file);
            Map<String, String> reasons = allowances(file, where, report);
            for (String label : reasons.keySet()) {
                if (!p.asserted().contains(label)) {
                    report.find("laws", where + " allows " + label + ", which no harness asserts: remove the stale allowance");
                } else if (!unmutated.contains(label)) {
                    report.find("laws", where + " allows " + label + ", which a matrix entry now targets: remove the stale allowance");
                }
            }
            Set<String> open = new TreeSet<>(unmutated);
            open.removeAll(reasons.keySet());
            allowed += unmutated.size() - open.size();
            if (hasFile) {
                audited++;
                if (unaudited.contains(id)) {
                    report.find("laws", LEDGER + " lists " + id + ", which has an " + ALLOWANCES + ": it is audited, so take it off the ledger");
                }
                for (String label : open) {
                    report.find("laws", id + " asserts " + label + " and no rejection-matrix entry targets it, and " + where
                            + " does not allow it: write a broken implementation that fails exactly this law, or say in the allowance why none is worth writing");
                }
            } else if (unaudited.contains(id)) {
                if (unmutated.isEmpty()) {
                    report.find("laws", LEDGER + " lists " + id + ", which has no unmutated label left: take it off the ledger");
                } else {
                    debt += unmutated.size();
                    debtPackages++;
                }
            } else if (!open.isEmpty()) {
                report.find("laws", id + " asserts " + open.size() + " label(s) no rejection-matrix entry targets (" + String.join(", ", first(open, 4))
                        + (open.size() > 4 ? ", ..." : "") + ") and has no " + where + ": write the broken implementations (CONTRACT-PACKAGE.md, rule 5),"
                        + " or allow each label with a reason");
            }
        }
        for (String id : unaudited) {
            if (!packages.containsKey(id)) {
                report.find("laws", LEDGER + " lists " + id + ", which is not a contract in the registry");
            }
        }
        report.result("laws", labels, labels + " labels asserted by " + packages.size() + " harnesses: " + mutated + " targeted by a rejection-matrix entry, "
                + allowed + " allowed with a reason (" + audited + " audited package(s)), " + debt + " unmutated in " + debtPackages
                + " package(s) not yet audited (" + LEDGER + ")");
    }

    private static List<String> first(Set<String> set, int n) {
        return set.stream().limit(n).toList();
    }
}
