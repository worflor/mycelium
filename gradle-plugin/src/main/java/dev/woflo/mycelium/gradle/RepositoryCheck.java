package dev.woflo.mycelium.gradle;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Mycelium repository checked against itself (docs/decisions/0004-the-repository-checks-itself.md).
 *
 * <p>The repository states facts in several places: a registry maps contracts to directories, packages follow a shape,
 * copied code must stay copied, the era table claims to cover every version, documents name counts and paths. Each of
 * those once drifted with nothing to notice. Every fact the repository can derive, it derives here and compares with
 * what it states. What cannot be derived (whether prose is true) stays with the author and the reviewer.
 *
 * <p>This class reads files only, so it can be run on a copy of the repository (the plugin's self-tests break a copy
 * in each way and prove each check fires). What only running can tell (each rejection matrix, as run) it is handed:
 * {@link RepositoryCheckTask} reads it from {@code myceliumVerify}'s results, on every {@code ./gradlew check}.
 */
public final class RepositoryCheck {
    /** Something the repository states that is not so. */
    public record Finding(String check, String message) {
        @Override
        public String toString() {
            return "[" + check + "] " + message;
        }
    }

    /** What one check looked at: so a check that looked at nothing says so. */
    public record Result(String check, int checked, String summary) {
    }

    public static final class Report {
        final List<Result> results = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();

        void result(String check, int checked, String summary) {
            results.add(new Result(check, checked, summary));
        }

        void find(String check, String message) {
            findings.add(new Finding(check, message));
        }

        public List<Result> results() {
            return List.copyOf(results);
        }

        public List<Finding> findings() {
            return List.copyOf(findings);
        }
    }

    /** Documents that describe the present; the others (CHANGELOG, DESIGN, archive, decision records) are history. */
    static final List<String> LIVING = List.of("README.md", "AGENTS.md", "CONTRACT-PACKAGE.md");

    /** Private copies of the shared harness a package may not keep (CONTRACT-PACKAGE.md, rule on the harness). */
    static final Set<String> SHARED_HARNESS = Set.of("Check.java", "ContractRun.java", "Suite.java", "Seeded.java");

    private RepositoryCheck() {
    }

    /**
     * @param matrices each contract's rejection-matrix size, by id, as its harness ran it ({@code myceliumVerify}'s
     *                 results): the harness knows its matrix, so nothing here parses a package's source to count one
     */
    public static Report run(Path root, Map<String, Integer> matrices) throws IOException {
        Report report = new Report();
        ContractRegistry registry;
        try {
            registry = ContractRegistry.load(root.toFile());
        } catch (RuntimeException e) {
            report.find("registry", e.getMessage());
            return report;
        }
        registryMatchesDisk(root, registry, report);
        packages(root, registry, matrices, report);
        models(root, registry, report);
        patterns(root, report);
        eras(root, report);
        docs(root, registry, matrices, report);
        return report;
    }

    // --- the registry and the directories -----------------------------------------------------------------------

    /** A package is a directory under {@code contracts/} with a {@code src/}: the rule the runner scripts use too. */
    static void registryMatchesDisk(Path root, ContractRegistry registry, Report report) throws IOException {
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(root.resolve("contracts"))) {
            dirs.filter(d -> Files.isDirectory(d.resolve("src"))).forEach(d -> onDisk.add(d.getFileName().toString()));
        }
        Set<String> rows = new TreeSet<>();
        for (ContractRegistry.Entry e : registry.all()) {
            rows.add(e.path());
            if (!onDisk.contains(e.path())) {
                report.find("registry", "row " + e.id() + " names contracts/" + e.path() + "/, which is not a package on disk"
                        + " (no such directory, or no src/ in it)");
            }
        }
        for (String dir : onDisk) {
            if (!rows.contains(dir)) {
                report.find("registry", "contracts/" + dir + "/ is a package on disk with no row in contracts/registry.json:"
                        + " the runner scripts would run it and every Gradle task would not");
            }
        }
        report.result("registry", rows.size(), rows.size() + " rows, " + onDisk.size() + " packages on disk");
    }

    // --- each package's shape ------------------------------------------------------------------------------------

    /**
     * Each package's shape: a SPEC, the three source directories, one {@code Run} where every runner looks, no private
     * copy of the shared harness, and a harness that reported. What a matrix must hold (a {@code NoOp*} entry, every
     * entry caught) is kept by {@code mycelium.harness.ContractRun}, where the matrix runs, whatever shape a Run has.
     */
    static void packages(Path root, ContractRegistry registry, Map<String, Integer> matrices, Report report) throws IOException {
        int reported = 0;
        for (ContractRegistry.Entry e : registry.all()) {
            Path dir = root.resolve("contracts").resolve(e.path());
            if (!Files.isDirectory(dir.resolve("src"))) {
                continue; // reported by registryMatchesDisk
            }
            String where = e.id() + " (contracts/" + e.path() + ")";
            if (!Files.isRegularFile(dir.resolve("SPEC.md"))) {
                report.find("package", where + " has no SPEC.md");
            }
            Path pkg = dir.resolve("src").resolve(e.pkg().replace('.', '/'));
            for (String part : List.of("model", "fixtures", "harness")) {
                if (!Files.isDirectory(pkg.resolve(part))) {
                    report.find("package", where + " has no " + part + "/ at src/" + e.pkg().replace('.', '/') + "/" + part);
                }
            }
            List<Path> runs = new ArrayList<>();
            List<Path> privateHarness = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(dir.resolve("src"))) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    if (name.equals("Run.java") && p.getParent().getFileName().toString().equals("harness")) {
                        runs.add(p);
                    }
                    if (SHARED_HARNESS.contains(name)) {
                        privateHarness.add(p);
                    }
                });
            }
            for (Path p : privateHarness) {
                report.find("package", where + " keeps its own " + p.getFileName() + " (" + rel(root, p)
                        + "): every package runs on the shared harness (harness/src), so every report reads alike");
            }
            Path run = root.resolve(e.runFile());
            if (runs.size() != 1 || !Files.isRegularFile(run)) {
                report.find("package", where + " must have exactly one harness/Run.java, at " + e.runFile()
                        + " (the main every runner starts: " + e.runMain() + "); found " + runs.size());
                continue;
            }
            if (matrices.containsKey(e.id())) {
                reported++;
            } else {
                report.find("package", where + " has no harness report: myceliumVerify did not run it (a"
                        + " -Pmycelium.contracts selection?), or its Run does not run on mycelium.harness.ContractRun");
            }
        }
        int total = matrices.values().stream().mapToInt(Integer::intValue).sum();
        report.result("package", registry.all().size(), registry.all().size() + " packages, " + reported
                + " harnesses reported, " + total + " rejection-matrix entries run");
    }

    // --- models: pure, and their own ---------------------------------------------------------------------------

    static final Pattern CONTRACT_REF = Pattern.compile("(?<![\\w.])mycelium\\.([a-z_][a-z0-9_]*)\\.v(\\d+)\\b");

    static void models(Path root, ContractRegistry registry, Report report) throws IOException {
        int files = 0;
        for (ContractRegistry.Entry e : registry.all()) {
            Path model = root.resolve("contracts").resolve(e.path()).resolve("src").resolve(e.pkg().replace('.', '/')).resolve("model");
            if (!Files.isDirectory(model)) {
                continue;
            }
            for (Path p : Harness.javaFiles(List.of(model))) {
                files++;
                String code = IsolationTask.code(Files.readString(p, StandardCharsets.UTF_8));
                if (IsolationTask.mentions(code, "mycelium.harness")) {
                    report.find("model", rel(root, p) + " mentions mycelium.harness: a model never depends on the harness");
                }
                Matcher m = CONTRACT_REF.matcher(code);
                while (m.find()) {
                    String ref = "mycelium." + m.group(1) + ".v" + m.group(2);
                    if (!ref.equals(e.pkg())) {
                        report.find("model", rel(root, p) + " reaches " + ref + ": a model imports only its own contract"
                                + " (a consumer imports the contracts it selects; a reference to another would not compile there)");
                    }
                }
            }
        }
        report.result("model", files, files + " model files, each pure and its own");
    }

    // --- copied patterns ----------------------------------------------------------------------------------------

    /**
     * Code a contract must copy, not invent ({@code contracts/patterns.json}): each copy is compared with the canonical
     * file after removing comments, the package line and the class's own name, and trimming each line. What text
     * comparison cannot see (a unicode escape spelling a different token) it does not see.
     */
    static void patterns(Path root, Report report) throws IOException {
        Path file = root.resolve("contracts/patterns.json");
        if (!Files.isRegularFile(file)) {
            report.result("pattern", 0, "no copied patterns declared");
            return;
        }
        JsonObject json = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
        int copies = 0;
        List<String> summary = new ArrayList<>();
        for (JsonElement el : json.getAsJsonArray("patterns")) {
            JsonObject p = el.getAsJsonObject();
            String name = p.get("name").getAsString();
            Path canonical = root.resolve(p.get("canonical").getAsString());
            if (!Files.isRegularFile(canonical)) {
                report.find("pattern", name + ": canonical file " + p.get("canonical").getAsString() + " does not exist");
                continue;
            }
            Pattern fileName = Pattern.compile(p.get("fileName").getAsString());
            Map<String, String> exempt = new LinkedHashMap<>();
            if (p.has("exempt")) {
                p.getAsJsonObject("exempt").entrySet().forEach(x -> exempt.put(x.getKey(), x.getValue().getAsString()));
            }
            List<String> reference = normalized(canonical);
            int found = 0;
            for (Path copy : modelFiles(root)) {
                if (!fileName.matcher(copy.getFileName().toString()).matches() || copy.equals(canonical)) {
                    continue;
                }
                found++;
                String where = rel(root, copy);
                if (exempt.containsKey(where)) {
                    summary.add(where + " exempt: " + exempt.get(where));
                    continue;
                }
                List<String> mine = normalized(copy);
                if (!mine.equals(reference)) {
                    int line = 0;
                    while (line < Math.min(mine.size(), reference.size()) && mine.get(line).equals(reference.get(line))) {
                        line++;
                    }
                    report.find("pattern", where + " is not a copy of the " + name + " pattern (" + p.get("canonical").getAsString()
                            + "): first difference at code line " + (line + 1) + ": '"
                            + (line < mine.size() ? mine.get(line) : "<end>") + "' against '"
                            + (line < reference.size() ? reference.get(line) : "<end>") + "'. Re-copy it, or exempt it with a reason");
                }
            }
            copies += found;
            summary.add(0, name + ": " + found + " copies of " + reference.size() + " code lines kept identical to the canonical");
        }
        report.result("pattern", copies, String.join("; ", summary));
    }

    static List<Path> modelFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root.resolve("contracts"))) {
            walk.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".java")
                    && p.getParent() != null && p.getParent().getFileName().toString().equals("model")
                    && !p.toString().contains(java.io.File.separator + "out" + java.io.File.separator)).forEach(out::add);
        }
        out.sort(null);
        return out;
    }

    static List<String> normalized(Path file) throws IOException {
        String cls = file.getFileName().toString().replace(".java", "");
        String code = IsolationTask.code(Files.readString(file, StandardCharsets.UTF_8));
        code = code.replaceAll("(?m)^\\s*package\\s+[\\w.]+\\s*;", "package PKG;");
        code = code.replaceAll("\\b" + Pattern.quote(cls) + "\\b", "CLS");
        List<String> out = new ArrayList<>();
        for (String line : code.split("\\R")) {
            if (!line.isBlank()) {
                out.add(line.strip());
            }
        }
        return out;
    }

    // --- the era and loader tables ------------------------------------------------------------------------------

    static void eras(Path root, Report report) throws IOException {
        Eras eras;
        Loaders loaders;
        try {
            eras = Eras.load(root.toFile());
            loaders = Loaders.load(root.toFile());
        } catch (RuntimeException e) {
            report.find("eras", e.getMessage());
            return;
        }
        Path versionsFile = root.resolve("eras/versions.json");
        if (!Files.isRegularFile(versionsFile)) {
            report.find("eras", "eras/versions.json is missing: nothing to prove the table against");
            return;
        }
        JsonObject vj = new Gson().fromJson(Files.readString(versionsFile, StandardCharsets.UTF_8), JsonObject.class);
        List<String> versions = new ArrayList<>();
        vj.getAsJsonArray("versions").forEach(v -> versions.add(v.getAsJsonObject().get("id").getAsString()));
        Set<String> real = new HashSet<>(versions);

        List<Eras.Era> all = eras.all();
        for (int i = 0; i < all.size(); i++) {
            Eras.Era e = all.get(i);
            for (String bound : List.of(e.from(), e.to())) {
                if (!Eras.isLine(bound) && !real.contains(bound)) {
                    report.find("eras", "era '" + e.id() + "': bound '" + bound + "' is not a version the launcher names"
                            + " (eras/versions.json). Name a real version, or a whole line ('" + lineOf(bound) + "')");
                }
            }
            if (!Eras.atMost(Eras.lineStart(e.from()), e.to())) {
                report.find("eras", "era '" + e.id() + "' runs backwards: from " + e.from() + " to " + e.to());
            }
            // newest first: the next row (older) ends before this one starts. Overlap between them is caught exactly by
            // the coverage check below (a real version in two eras).
            if (i + 1 < all.size() && Eras.compare(Eras.lineStart(all.get(i + 1).to()), Eras.lineStart(e.from())) >= 0) {
                report.find("eras", "eras '" + all.get(i + 1).id() + "' and '" + e.id() + "' are out of order"
                        + " (the table lists the newest era first)");
            }
            for (String loader : e.loaders()) {
                if (!loaders.loaders().containsKey(loader)) {
                    report.find("eras", "era '" + e.id() + "' names the loader '" + loader + "', which eras/loaders.json does not:"
                            + " the pure layer would not keep its packages out");
                }
            }
        }
        int covered = 0;
        int outside = 0;
        for (String v : versions) {
            if (!Eras.readable(v)) {
                outside++; // before Classic (rd-, in-, inf-): not targets, and the table does not claim them
                continue;
            }
            List<String> in = new ArrayList<>();
            for (Eras.Era e : all) {
                if (e.covers(v)) {
                    in.add(e.id());
                }
            }
            if (in.size() != 1) {
                report.find("eras", "Minecraft " + v + " falls in " + (in.isEmpty() ? "no era" : "several eras " + in)
                        + ": every real version must fall in exactly one");
            } else {
                covered++;
            }
        }
        report.result("eras", covered, all.size() + " eras cover " + covered + " real versions exactly once ("
                + outside + " before Classic are not targets); " + loaders.loaders().size() + " loaders, "
                + loaders.platformPackages().size() + " platform package roots");
    }

    private static String lineOf(String version) {
        String core = version.replaceAll("-.*$", "");
        int dot = core.lastIndexOf('.');
        return (dot > 0 ? core.substring(0, dot) : core) + ".x";
    }

    // --- documents ----------------------------------------------------------------------------------------------

    /** A count stated in prose: it drifts the day the thing it counts changes (a document once said twelve of fifteen). */
    static final Pattern COUNT = Pattern.compile("(?i)\\b(\\d{1,3}|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
            + "|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)\\s+(contract packages|packages|contracts"
            + "|rejection fixtures|rejection-matrix entries|matrix entries|fixtures|harnesses|tasks|era gates|eras|plugin classes)\\b");

    static final Pattern BACKTICK = Pattern.compile("`([^`\\n]+)`");

    /** Tokens in documents that name a path in this repository, checked to exist. */
    static final List<String> PATH_ROOTS = List.of("contracts/", "harness/", "gradle-plugin/", "docs/", "eras/", "archive/",
            "build.gradle.kts", "settings.gradle.kts", "run-contracts.sh", "run-contracts.ps1", "README.md", "AGENTS.md",
            "DESIGN.md", "CONTRACT-PACKAGE.md", "CHANGELOG.md");

    static void docs(Path root, ContractRegistry registry, Map<String, Integer> matrices, Report report) throws IOException {
        List<Path> living = new ArrayList<>();
        for (String d : LIVING) {
            if (Files.isRegularFile(root.resolve(d))) {
                living.add(root.resolve(d));
            }
        }
        try (Stream<Path> docs = Files.list(root.resolve("docs"))) {
            docs.filter(p -> p.toString().endsWith(".md")).sorted().forEach(living::add);
        }
        int packages = registry.all().size();
        // the fixture count is derivable only when every package's harness reported (packages() names any that did not)
        Integer entries = registry.all().stream().allMatch(e -> matrices.containsKey(e.id()))
                ? matrices.values().stream().mapToInt(Integer::intValue).sum() : null;
        int paths = 0;
        for (Path doc : living) {
            List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String where = rel(root, doc) + ":" + (i + 1);
                boolean status = doc.getFileName().toString().equals("README.md") && line.startsWith("| Status |");
                Matcher c = COUNT.matcher(line);
                while (c.find()) {
                    if (!status) {
                        report.find("docs", where + " states a count (\"" + c.group() + "\"). Counts live in README.md's"
                                + " status line, where this check derives them; elsewhere, point at the thing counted");
                    }
                }
                if (status) {
                    statusLine(line, where, packages, entries, report);
                }
                paths += paths(root, where, line, List.of(root, doc.getParent()), report);
            }
        }
        // a contract's own documents: they name files relative to themselves or to their package's source
        int contractDocs = 0;
        for (ContractRegistry.Entry e : registry.all()) {
            Path dir = root.resolve("contracts").resolve(e.path());
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> bases = List.of(root, dir, dir.resolve("src").resolve(e.pkg().replace('.', '/')));
            try (Stream<Path> docs = Files.list(dir)) {
                for (Path doc : docs.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                    contractDocs++;
                    List<String> lines = Files.readAllLines(doc, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        paths += paths(root, rel(root, doc) + ":" + (i + 1), lines.get(i), bases, report);
                    }
                }
            }
        }
        // the plugin's map in AGENTS.md names every class the plugin is made of
        Path plugin = root.resolve("gradle-plugin/src/main/java/dev/woflo/mycelium/gradle");
        Path agents = root.resolve("AGENTS.md");
        int classes = 0;
        if (Files.isDirectory(plugin) && Files.isRegularFile(agents)) {
            String text = Files.readString(agents, StandardCharsets.UTF_8);
            for (Path p : Harness.javaFiles(List.of(plugin))) {
                classes++;
                if (!Pattern.compile("\\b" + Pattern.quote(p.getFileName().toString()) + "\\b").matcher(text).find()) {
                    report.find("docs", "AGENTS.md does not name " + p.getFileName() + ": its map of the plugin is every class");
                }
            }
            // and README.md's task table names every task the plugin registers
            Path readme = root.resolve("README.md");
            String readmeText = Files.isRegularFile(readme) ? Files.readString(readme, StandardCharsets.UTF_8) : "";
            for (String task : registeredTasks(root)) {
                if (!readmeText.contains("`" + task)) {
                    report.find("docs", "README.md does not name the task " + task + ", which the plugin registers");
                }
            }
        }
        report.result("docs", living.size() + contractDocs, living.size() + " living documents and " + contractDocs
                + " contract documents: counts derived, " + paths + " paths resolved, " + classes + " plugin classes mapped");
    }

    /**
     * Checks every path one line of a document names. A path names a file in this repository, so it must exist here:
     * resolved from any of {@code bases} (the document's own directory among them, as a reader follows a link), and
     * never outside the repository. A document that sends its reader into another repository points at something that
     * moves on its own schedule and is not versioned with what it describes; say in words what was learned there.
     *
     * @return how many paths the line names
     */
    static int paths(Path root, String where, String line, List<Path> bases, Report report) {
        Path top = root.toAbsolutePath().normalize();
        int n = 0;
        Matcher b = BACKTICK.matcher(line);
        while (b.find()) {
            String token = b.group(1).trim();
            if (!looksLikePath(token, bases)) {
                continue;
            }
            n++;
            String path = token.replaceAll("[.,:;)]+$", "");
            boolean outside = true;
            boolean found = false;
            for (Path base : bases) {
                Path p = base.toAbsolutePath().normalize().resolve(path).normalize();
                if (p.startsWith(top)) {
                    outside = false;
                    found |= Files.exists(p);
                }
            }
            if (!found) {
                report.find("docs", where + " names `" + token + "`, which "
                        + (outside ? "is outside this repository (say what was learned there in words)" : "does not exist"));
            }
        }
        return n;
    }

    static final Pattern REGISTER = Pattern.compile("register\\(\\s*\"(mycelium[A-Za-z]+)\"");

    /** Every task the plugins register, read from their source (one per {@code register("mycelium...")}). */
    static List<String> registeredTasks(Path root) throws IOException {
        TreeSet<String> out = new TreeSet<>();
        Path plugin = root.resolve("gradle-plugin/src/main/java/dev/woflo/mycelium/gradle");
        for (Path p : Harness.javaFiles(List.of(plugin))) {
            Matcher m = REGISTER.matcher(Files.readString(p, StandardCharsets.UTF_8));
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return new ArrayList<>(out);
    }

    static final Pattern STATUS_PACKAGES = Pattern.compile("(\\d+) packages");
    static final Pattern STATUS_ENTRIES = Pattern.compile("(\\d+) rejection fixtures");

    static void statusLine(String line, String where, int packages, Integer entries, Report report) {
        Matcher p = STATUS_PACKAGES.matcher(line);
        if (p.find() && Integer.parseInt(p.group(1)) != packages) {
            report.find("docs", where + " says " + p.group() + "; the registry has " + packages);
        }
        Matcher e = STATUS_ENTRIES.matcher(line);
        if (e.find() && entries != null && Integer.parseInt(e.group(1)) != entries) {
            report.find("docs", where + " says " + e.group() + "; the harnesses' rejection matrices hold " + entries);
        }
    }

    /**
     * Whether a backticked token is a path: it climbs ({@code ../}), starts at one of the repository's known roots, or
     * starts with a directory that exists under one of {@code bases} ({@code model/Keeper.java} in a contract). A bare
     * name ({@code fabric.mod.json}) is not a path: documents talk about files in consumers' projects too.
     */
    static boolean looksLikePath(String token, List<Path> bases) {
        if (token.contains("<") || token.contains("*") || token.contains("{") || token.contains(" ") || token.contains("...")
                || token.contains("$") || token.contains("://") || token.contains("\\")) {
            return false;
        }
        if (token.contains("/build/") || token.startsWith("build/")) {
            return false;
        }
        if (token.startsWith("../") || token.startsWith("./")) {
            return true;
        }
        for (String r : PATH_ROOTS) {
            if (token.equals(r) || token.startsWith(r)) {
                return true;
            }
        }
        int slash = token.indexOf('/');
        if (slash <= 0) {
            return false;
        }
        String first = token.substring(0, slash);
        for (Path base : bases) {
            if (Files.isDirectory(base.resolve(first))) {
                return true;
            }
        }
        return false;
    }

    static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }
}
