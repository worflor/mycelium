package dev.woflo.mycelium.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The repository check's half for bridges (docs/decisions/0016): what {@code bridges/} states is so. Every bridge reads
 * strictly, names a contract whose registry row lists it (and the other way round), implements a port that exists in that
 * contract's model, and has a variant for each realization its divide's table gives a version, with every version in exactly
 * one row (which {@link RepositoryCheck} proves for every table). Every overlay is a directory with a range that falls inside
 * its variant's and touches no other overlay's. What only a game can say (that a variant's references exist on each version
 * it covers) is {@link BridgeLinkage}, in {@code myceliumBridgeLinkage}.
 */
final class BridgeCheck {
    private BridgeCheck() {
    }

    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    static void run(Path root, ContractRegistry registry, RepositoryCheck.Report report) throws IOException {
        List<Bridges.Bridge> bridges = new ArrayList<>();
        Path dir = root.resolve("bridges");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> list = Files.list(dir)) {
                for (Path d : (Iterable<Path>) list.filter(Files::isDirectory).sorted()::iterator) {
                    Path file = d.resolve("bridge.json");
                    if (!Files.isRegularFile(file)) {
                        report.find("bridges", "bridges/" + d.getFileName() + "/ has no bridge.json: a directory under bridges/ is a bridge");
                        continue;
                    }
                    try {
                        bridges.add(Bridges.parse(Files.readString(file, StandardCharsets.UTF_8), d.getFileName().toString(), d,
                                "bridges/" + d.getFileName() + "/bridge.json"));
                    } catch (RuntimeException e) {
                        report.find("bridges", e.getMessage());
                    }
                }
            }
        }
        Eras eras;
        NameData names;
        List<String> versions;
        try {
            eras = Eras.load(root.toFile());
            names = NameData.load(root.resolve("eras/names.json").toFile());
            versions = Versions.load(root.toFile());
        } catch (RuntimeException e) {
            report.find("bridges", "the era data could not be read, so the bridges cannot be held to it: " + e.getMessage());
            return;
        }
        Set<String> slugs = new TreeSet<>();
        int overlays = 0;
        int files = 0;
        for (Bridges.Bridge b : bridges) {
            slugs.add(b.slug());
            String where = "bridge " + b.slug();
            // the contract and the port
            ContractRegistry.Entry contract = null;
            try {
                contract = registry.require(b.contract());
            } catch (IllegalStateException e) {
                report.find("bridges", where + " implements the port of " + b.contract() + ", which is not a contract in the registry");
            }
            if (contract != null) {
                if (!contract.bridges().contains(b.slug())) {
                    report.find("bridges", where + " names " + b.contract() + ", whose registry row does not list it in \"bridges\": importing the contract"
                            + " would not bring it");
                }
                Path port = root.resolve("contracts").resolve(contract.path()).resolve("src").resolve(contract.pkg().replace('.', '/'))
                        .resolve("model").resolve(b.port() + ".java");
                if (!Files.isRegularFile(port)) {
                    report.find("bridges", where + ": the port " + b.port() + " is not in " + b.contract() + "'s model (" + RepositoryCheck.rel(root, port) + ")");
                } else if (!Pattern.compile("\\binterface\\s+" + Pattern.quote(b.port()) + "\\b").matcher(Files.readString(port, StandardCharsets.UTF_8)).find()) {
                    report.find("bridges", where + ": the port " + b.port() + " is not an interface");
                }
            }
            for (String namespace : b.writtenIn()) {
                if (!names.namespaces().contains(namespace)) {
                    report.find("bridges", where + " is written in the names of '" + namespace + "', which eras/names.json has no data for");
                }
            }
            // the realization table: every row names a variant (or none), every variant is drawn somewhere
            List<Eras.Realization> table = eras.realizations(b.divide());
            if (table.isEmpty()) {
                report.find("bridges", where + ": eras/minecraft.json has no \"" + b.divide() + "\" realization table, so no version knows which variant it draws");
            }
            Set<String> drawn = new TreeSet<>();
            for (Eras.Realization row : table) {
                if (row.realization().equals("none")) {
                    continue;
                }
                drawn.add(row.realization());
                if (!b.variants().containsKey(row.realization())) {
                    report.find("bridges", where + ": the " + b.divide() + " table gives " + row.from() + ".." + row.to() + " the realization '"
                            + row.realization() + "', which the bridge has no variant for (it has " + new TreeSet<>(b.variants().keySet()) + ")");
                }
            }
            for (String variant : b.variants().keySet()) {
                if (!drawn.contains(variant) && !table.isEmpty()) {
                    report.find("bridges", where + ": variant " + variant + " is drawn by no row of the " + b.divide() + " table: it is dead source");
                }
            }
            // the layout, variant by variant
            Path sharedConformance = b.dir().resolve("conformance");
            boolean hasConformance = !Bridges.javaFiles(sharedConformance).isEmpty();
            for (Bridges.Variant v : b.variants().values()) {
                Path vdir = b.variantDir(v.name());
                Map<String, Path> src = Bridges.javaFiles(vdir.resolve("src"));
                if (src.isEmpty()) {
                    report.find("bridges", where + ": variant " + v.name() + " has no source (" + RepositoryCheck.rel(root, vdir.resolve("src")) + ")");
                }
                String implementation = b.pkg().replace('.', '/') + "/" + b.implementation() + ".java";
                if (!src.containsKey(implementation)) {
                    report.find("bridges", where + ": variant " + v.name() + " does not define " + b.implementation() + " ("
                            + RepositoryCheck.rel(root, vdir.resolve("src").resolve(implementation)) + ")");
                } else if (!Pattern.compile("\\bimplements\\b[^{]*\\b" + Pattern.quote(b.port()) + "\\b").matcher(Files.readString(src.get(implementation), StandardCharsets.UTF_8)).find()) {
                    report.find("bridges", where + ": " + b.implementation() + " of variant " + v.name() + " does not implement " + b.port());
                }
                if (hasConformance && Bridges.javaFiles(vdir.resolve("conformance")).isEmpty()) {
                    report.find("bridges", where + ": the bridge has conformance cases, and variant " + v.name()
                            + " has none of its own to run them with (" + RepositoryCheck.rel(root, vdir.resolve("conformance")) + ")");
                }
                files += sources(root, b, vdir.resolve("src"), report) + sources(root, b, vdir.resolve("conformance"), report);
                // overlays: directories, with ranges inside the variant's that do not meet
                Set<String> named = new TreeSet<>();
                for (Bridges.Overlay o : v.overlays()) {
                    overlays++;
                    String at = where + ": overlay " + o.name() + " of " + v.name();
                    if (!named.add(o.name())) {
                        report.find("bridges", at + " is listed twice");
                    }
                    Path odir = vdir.resolve("overlays").resolve(o.name());
                    if (Bridges.javaFiles(odir).isEmpty()) {
                        report.find("bridges", at + " has no files (" + RepositoryCheck.rel(root, odir) + ")");
                    }
                    files += sources(root, b, odir.resolve("src"), report) + sources(root, b, odir.resolve("conformance"), report);
                    for (String bound : List.of(o.from(), o.to())) {
                        if (!Eras.isLine(bound) && !versions.contains(bound)) {
                            report.find("bridges", at + ": bound '" + bound + "' is not a version the launcher names");
                        }
                    }
                    if (Eras.readable(Eras.lineStart(o.from())) && Eras.readable(Eras.lineStart(o.to())) && !Eras.atMost(Eras.lineStart(o.from()), o.to())) {
                        report.find("bridges", at + " runs backwards: from " + o.from() + " to " + o.to());
                    }
                    int covered = 0;
                    for (String version : versions) {
                        if (!Eras.readable(version) || !o.covers(version)) {
                            continue;
                        }
                        covered++;
                        Eras.Realization row = eras.realization(b.divide(), version);
                        if (row == null || !row.realization().equals(v.name())) {
                            report.find("bridges", at + " covers Minecraft " + version + ", which the " + b.divide() + " table gives "
                                    + (row == null ? "no row" : "'" + row.realization() + "'") + ": an overlay lies inside its variant's range");
                            break;
                        }
                    }
                    if (covered == 0) {
                        report.find("bridges", at + " covers no real version");
                    }
                    for (Bridges.Overlay other : v.overlays()) {
                        if (other != o && other.name().compareTo(o.name()) > 0 && versions.stream().anyMatch(x -> Eras.readable(x) && o.covers(x) && other.covers(x))) {
                            report.find("bridges", at + " and overlay " + other.name() + " cover the same version: each version draws one overlay's files");
                        }
                    }
                }
                Path overlaysDir = vdir.resolve("overlays");
                if (Files.isDirectory(overlaysDir)) {
                    try (Stream<Path> list = Files.list(overlaysDir)) {
                        for (Path o : (Iterable<Path>) list.filter(Files::isDirectory)::iterator) {
                            if (!named.contains(o.getFileName().toString())) {
                                report.find("bridges", where + ": " + RepositoryCheck.rel(root, o) + " is an overlay directory bridge.json does not list: no version draws it");
                            }
                        }
                    }
                }
            }
            files += sources(root, b, sharedConformance, report);
        }
        // the registry's side: every bridge a row lists exists, and names that contract
        for (ContractRegistry.Entry e : registry.all()) {
            for (String slug : e.bridges()) {
                Bridges.Bridge b = bridges.stream().filter(x -> x.slug().equals(slug)).findFirst().orElse(null);
                if (b == null) {
                    report.find("bridges", "row " + e.id() + " lists the bridge '" + slug + "', which is not a directory under bridges/ with a bridge.json");
                } else if (!b.contract().equals(e.id())) {
                    report.find("bridges", "row " + e.id() + " lists the bridge '" + slug + "', which implements the port of " + b.contract());
                }
            }
        }
        report.result("bridges", bridges.size(), bridges.size() + " bridges (" + String.join(", ", slugs) + "): " + overlays + " overlays, "
                + files + " sources laid out in their packages, each variant drawn by the realization table, each overlay inside its variant");
    }

    /** Each source under {@code dir} sits in the bridge's package, declares it, and does not reach the harness. Returns how many. */
    private static int sources(Path root, Bridges.Bridge b, Path dir, RepositoryCheck.Report report) throws IOException {
        int n = 0;
        String expected = b.pkg();
        for (Map.Entry<String, Path> f : Bridges.javaFiles(dir).entrySet()) {
            n++;
            String text = Files.readString(f.getValue(), StandardCharsets.UTF_8);
            if (text.contains("mycelium.harness")) {
                report.find("bridges", RepositoryCheck.rel(root, f.getValue()) + " mentions mycelium.harness: a bridge never depends on the harness");
            }
            Matcher m = PACKAGE.matcher(text);
            String declared = m.find() ? m.group(1) : "";
            String path = f.getKey().contains("/") ? f.getKey().substring(0, f.getKey().lastIndexOf('/')).replace('/', '.') : "";
            if (!declared.equals(path)) {
                report.find("bridges", RepositoryCheck.rel(root, f.getValue()) + " declares package '" + declared + "' but sits in '" + path + "'");
            } else if (!declared.equals(expected) && !declared.startsWith(expected + ".")) {
                report.find("bridges", RepositoryCheck.rel(root, f.getValue()) + " is in package '" + declared + "', not the bridge's " + expected
                        + ": a bridge's files are all its own, moved under the mod's namespace");
            }
        }
        return n;
    }
}
