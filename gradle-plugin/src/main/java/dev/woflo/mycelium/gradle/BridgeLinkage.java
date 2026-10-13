package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Linkage of a bridge's every variant against every version its realization row covers (docs/decisions/0016, "Proof in
 * Mycelium's gate"). A bridge is source written in Mojang's names, so it is held to the published names of the versions it
 * claims, in Mycelium's own check, before any mod builds it.
 *
 * <p>For each variant, the versions its row covers are grouped by the overlays that cover them (a group is the exact source
 * a mod draws on those versions). Each group's source, its conformance cases with it, is <em>compiled</em> against the
 * newest of its versions that has name data (the pinned game archive, renamed to Mojang's names, with the libraries it ships
 * with), so a signature, an overload or a type that does not exist there is a compile error naming it. The compiled bytecode's
 * every class, method and field reference is then held to the published names of <em>every</em> version of the group that
 * has name data ({@link LinkageGate}), which catches what a compile on one version cannot: a member another version lacks, as
 * a {@code NoSuchMethodError} would on that version. A version with no name data is reported, not failed (decision 0017).
 *
 * <p>Compiling, rather than reading references out of the source text, is the one way to know which method a call names: the
 * receiver's type, the overload and the inherited owner are all the compiler's to resolve.
 */
final class BridgeLinkage {
    private BridgeLinkage() {
    }

    /** What the run found: {@code findings} fail ({@code L1}), {@code notes} are versions left unlinked ({@code L2}). */
    record Result(List<String> findings, List<String> notes, JsonObject report) {
    }

    /** Annotation packages whose classes the game's libraries do not ship but its classes mention: stubbed so a compile can read them. */
    private static final List<String> ANNOTATIONS = List.of("org.jspecify.", "javax.annotation.", "org.jetbrains.annotations.",
            "org.checkerframework.", "com.google.errorprone.", "org.jetbrains.");

    private static final Pattern MISSING = Pattern.compile("class file for ([\\w.$]+) not found");
    private static final Pattern WRONG_VERSION = Pattern.compile("class file has wrong version (\\d+)\\.\\d+, should be (\\d+)\\.\\d+");

    /** Every bridge under {@code root}, held to the name data under {@code root}. */
    static Result run(Path root, NameCache cache, Path work, Path javac) throws IOException {
        Eras eras = Eras.load(root.toFile());
        NameData data = NameData.load(root.resolve("eras/names.json").toFile());
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        List<String> versions = Versions.load(root.toFile());
        List<String> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        JsonObject report = new JsonObject();
        report.addProperty("schema", 1);
        JsonArray bridges = new JsonArray();
        for (Bridges.Bridge bridge : Bridges.load(root)) {
            ContractRegistry.Entry contract = registry.require(bridge.contract());
            List<Path> models = new ArrayList<>();
            for (ContractRegistry.Entry e : registry.withRequirements(List.of(contract))) {
                models.add(root.resolve("contracts").resolve(e.path()).resolve("src"));
            }
            Result one = bridge(bridge, models, eras, data, cache, versions, work.resolve(bridge.slug()), javac);
            findings.addAll(one.findings());
            notes.addAll(one.notes());
            bridges.add(one.report());
        }
        report.add("bridges", bridges);
        return new Result(findings, notes, report);
    }

    /** One bridge: every variant, in every group of versions that draw the same source. */
    static Result bridge(Bridges.Bridge bridge, List<Path> models, Eras eras, NameData data, NameCache cache, List<String> versions,
                         Path work, Path javac) throws IOException {
        List<String> findings = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        JsonObject report = new JsonObject();
        report.addProperty("slug", bridge.slug());
        JsonArray variants = new JsonArray();
        List<String> none = new ArrayList<>();
        for (String v : versions) {
            Eras.Realization row = Eras.readable(v) ? eras.realization(bridge.divide(), v) : null;
            if (row != null && row.realization().equals("none")) {
                none.add(v);
            }
        }
        for (Bridges.Variant variant : bridge.variants().values()) {
            // the versions this variant draws, grouped by the overlays that cover them: each group is one source
            Map<String, List<String>> groups = new LinkedHashMap<>();
            Map<String, Bridges.Selection> selections = new LinkedHashMap<>();
            for (String v : versions) {
                if (!Eras.readable(v)) {
                    continue;
                }
                Eras.Realization row = eras.realization(bridge.divide(), v);
                if (row == null || !row.realization().equals(variant.name())) {
                    continue;
                }
                Bridges.Selection s = Bridges.select(bridge, eras, v);
                String key = s.overlays().stream().map(Bridges.Overlay::name).toList().toString();
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
                selections.putIfAbsent(key, s);
            }
            JsonObject vo = new JsonObject();
            vo.addProperty("variant", variant.name());
            JsonArray groupsJson = new JsonArray();
            for (Map.Entry<String, List<String>> g : groups.entrySet()) {
                groupsJson.add(group(bridge, selections.get(g.getKey()), g.getValue(), models, data, cache, work, javac, findings, notes));
            }
            vo.add("groups", groupsJson);
            variants.add(vo);
        }
        report.add("variants", variants);
        JsonArray noneJson = new JsonArray();
        none.forEach(noneJson::add);
        report.add("noRealization", noneJson);
        return new Result(findings, notes, report);
    }

    private static JsonObject group(Bridges.Bridge bridge, Bridges.Selection selection, List<String> versions, List<Path> models, NameData data,
                                    NameCache cache, Path work, Path javac, List<String> findings, List<String> notes) throws IOException {
        String label = "bridge " + bridge.slug() + ", variant " + selection.describe();
        JsonObject out = new JsonObject();
        JsonArray overlays = new JsonArray();
        selection.overlays().forEach(o -> overlays.add(o.name()));
        out.add("overlays", overlays);
        out.addProperty("versions", versions.size() + " (" + versions.get(versions.size() - 1) + " to " + versions.get(0) + ")");

        // the versions of the group that have name data in the names the bridge is written in, newest first
        Map<String, NameData.Entry> withData = new LinkedHashMap<>();
        List<String> withoutData = new ArrayList<>();
        for (String v : versions) {
            NameData.Entry entry = null;
            for (String namespace : bridge.writtenIn()) {
                entry = data.entry(namespace, v);
                if (entry != null) {
                    break;
                }
            }
            if (entry == null) {
                withoutData.add(v);
            } else {
                withData.put(v, entry);
            }
        }
        out.add("noNameData", array(withoutData));
        if (withData.isEmpty()) {
            out.addProperty("status", "unlinked");
            out.addProperty("why", "no name data is pinned in " + bridge.writtenIn() + " for any of its versions (eras/names.json)");
            notes.add(label + ": not linked on any of its " + versions.size() + " versions: no name data is pinned in " + bridge.writtenIn());
            return out;
        }
        String representative = withData.keySet().iterator().next();
        out.addProperty("compiledAgainst", representative);

        Compiled compiled;
        try {
            Path classpathDir = cache.dir().resolve("classpath");
            Files.createDirectories(classpathDir);
            List<Path> jars = GameClasspath.of(withData.get(representative), cache, classpathDir);
            compiled = compile(selection, models, jars, work.resolve(selection.describe().replace(' ', '_').replace('+', '_')), javac);
        } catch (NameCache.Unavailable u) {
            out.addProperty("status", "unavailable");
            out.addProperty("why", u.getMessage());
            notes.add(label + ": not compiled, the game of " + representative + " could not be had: " + u.getMessage());
            return out;
        }
        if (compiled.unavailable() != null) {
            out.addProperty("status", "unavailable");
            out.addProperty("why", compiled.unavailable());
            notes.add(label + ": not compiled against " + representative + ": " + compiled.unavailable());
            return out;
        }
        if (compiled.classes() == null) {
            out.addProperty("status", "broken");
            out.addProperty("compile", compiled.diagnostics());
            findings.add(label + " does not compile against Minecraft " + representative + " (the newest of its versions with name data):\n      "
                    + compiled.diagnostics().replace("\n", "\n      "));
            return out;
        }
        out.addProperty("compile", "ok: " + compiled.classes().size() + " classes against Minecraft " + representative);
        References.Found found = References.scan(compiled.classes(), Set.of());

        JsonArray linked = new JsonArray();
        JsonArray broken = new JsonArray();
        JsonArray unavailable = new JsonArray();
        for (Map.Entry<String, NameData.Entry> e : withData.entrySet()) {
            String v = e.getKey();
            try {
                Names names = Names.read(e.getValue(), cache);
                LinkageGate.Check c = LinkageGate.check(found, e.getValue(), names);
                if (c.unresolved().isEmpty()) {
                    linked.add(v);
                } else {
                    JsonObject b = new JsonObject();
                    b.addProperty("version", v);
                    JsonArray refs = new JsonArray();
                    c.unresolved().stream().limit(LinkageGate.LISTED).forEach(u -> refs.add(u.text()));
                    b.add("unresolved", refs);
                    b.addProperty("count", c.unresolved().size());
                    broken.add(b);
                    StringBuilder f = new StringBuilder(label + ": Minecraft " + v + ": " + c.unresolved().size() + " reference"
                            + (c.unresolved().size() == 1 ? "" : "s") + " do" + (c.unresolved().size() == 1 ? "es" : "")
                            + " not resolve in " + e.getValue().namespace() + " " + v + " (the bridge claims it maps there):");
                    c.unresolved().stream().limit(LinkageGate.LISTED).forEach(u -> f.append("\n      ").append(u.text()));
                    findings.add(f.toString());
                }
            } catch (NameCache.Unavailable u) {
                unavailable.add(v);
                notes.add(label + ": Minecraft " + v + " not linked, its " + e.getValue().namespace() + " name data could not be had: " + u.getMessage());
            }
        }
        out.add("linked", linked);
        out.add("broken", broken);
        out.add("unavailable", unavailable);
        out.addProperty("status", broken.isEmpty() ? (unavailable.isEmpty() ? "linked" : "unavailable") : "broken");
        if (!withoutData.isEmpty()) {
            notes.add(label + ": " + withoutData.size() + " of its " + versions.size() + " versions have no name data and are not linked ("
                    + String.join(", ", withoutData.subList(0, Math.min(6, withoutData.size()))) + (withoutData.size() > 6 ? ", ..." : "") + ")");
        }
        return out;
    }

    private static JsonArray array(List<String> items) {
        JsonArray a = new JsonArray();
        items.forEach(a::add);
        return a;
    }

    // ---------------------------------------------------------------------------------------------------- compiling

    /** The classes of a compile (internal name to bytes), or why there are none. */
    record Compiled(Map<String, byte[]> classes, String diagnostics, String unavailable) {
    }

    /** The compiler of the JVM running the build: where nothing says otherwise. */
    static Path runningJavac() {
        return Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "javac.exe" : "javac");
    }

    /**
     * The selection's sources (a variant, its overlays and the conformance cases), compiled as written, against {@code jars}
     * with the contract models on the source path. {@code javac} is the compiler to run: it has to be at least as new as the
     * game's class files (a game that runs on Java 25 is read by a javac of 25 or later), which the plugin cannot assume of the
     * JVM running the build, so the build hands it a toolchain's.
     */
    static Compiled compile(Bridges.Selection selection, List<Path> models, List<Path> jars, Path work, Path javac) throws IOException {
        if (javac == null || !Files.isRegularFile(javac)) {
            return new Compiled(null, null, "no Java compiler to compile the bridge with" + (javac == null ? "" : " (" + javac + ")") + "; run Gradle on a JDK");
        }
        deleteRecursively(work);
        Path src = work.resolve("src");
        Path classes = work.resolve("classes");
        Path stubSources = work.resolve("stub-src");
        Path stubClasses = work.resolve("stubs");
        Files.createDirectories(src);
        Files.createDirectories(classes);
        Files.createDirectories(stubSources);
        Files.createDirectories(stubClasses);
        List<Path> sources = new ArrayList<>();
        Map<String, Path> files = new java.util.TreeMap<>(Bridges.sourceFiles(selection));
        files.putAll(Bridges.conformanceFiles(selection));
        for (Map.Entry<String, Path> f : files.entrySet()) {
            Path dest = src.resolve(f.getKey());
            Files.createDirectories(dest.getParent());
            Files.copy(f.getValue(), dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            sources.add(dest);
        }
        List<String> stubbed = new ArrayList<>();
        String separator = File.pathSeparator;
        for (int attempt = 0; attempt < 12; attempt++) {
            List<String> classpath = new ArrayList<>();
            jars.forEach(j -> classpath.add(j.toString()));
            classpath.add(stubClasses.toString());
            List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-Xlint:none", "-implicit:class", "-encoding", "UTF-8",
                    "-d", classes.toString(), "-cp", String.join(separator, classpath), "-sourcepath",
                    models.stream().map(Path::toString).collect(java.util.stream.Collectors.joining(separator))));
            sources.forEach(x -> args.add(x.toString()));
            Run run = run(javac, args, work.resolve("args-" + attempt + ".txt"));
            if (run.exit() == 0) {
                return new Compiled(read(classes), null, null);
            }
            Matcher wrong = WRONG_VERSION.matcher(run.output());
            if (wrong.find()) {
                return new Compiled(null, null, "the compiler used (" + javac + ") cannot read the game's class files (version " + wrong.group(1)
                        + "): it is older than the Java the game runs on");
            }
            boolean stubbedOne = false;
            Matcher missing = MISSING.matcher(run.output());
            while (missing.find()) {
                if (stubAnnotation(missing.group(1), stubSources, stubClasses, javac, stubbed)) {
                    stubbedOne = true;
                }
            }
            if (!stubbedOne) {
                return new Compiled(null, errors(run.output()), null);
            }
        }
        return new Compiled(null, "the compile kept asking for classes the game's libraries do not ship", null);
    }

    private record Run(int exit, String output) {
    }

    private static Run run(Path javac, List<String> args, Path argsFile) throws IOException {
        // an argument file: a classpath of a game and its libraries is longer than a command line may be
        List<String> quoted = new ArrayList<>();
        for (String a : args) {
            quoted.add("\"" + a.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        Files.write(argsFile, quoted, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(javac.toString(), "@" + argsFile).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return new Run(process.waitFor(), output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while compiling a bridge", e);
        }
    }

    private static final Pattern ERROR_LINE = Pattern.compile("^.*?([^\\\\/\\s]+:\\d+): error: (.*)$");

    /** The compiler's error lines (each with what follows it), without its summary, at most a dozen. */
    private static String errors(String output) {
        List<String> blocks = new ArrayList<>();
        StringBuilder at = null;
        for (String line : output.replace("\r", "").split("\n")) {
            Matcher m = ERROR_LINE.matcher(line);
            if (m.matches()) {
                if (at != null) {
                    blocks.add(at.toString());
                }
                at = new StringBuilder(m.group(1) + ": " + m.group(2));
            } else if (at != null && line.startsWith("  ") && !line.isBlank()) {
                at.append(" | ").append(line.trim());
            } else if (at != null && !line.startsWith("  ") && !line.isBlank() && !line.matches("\\s*\\^\\s*")) {
                blocks.add(at.toString());
                at = null;
            }
        }
        if (at != null) {
            blocks.add(at.toString());
        }
        if (blocks.isEmpty()) {
            return output.strip();
        }
        List<String> shown = blocks.size() > 12 ? blocks.subList(0, 12) : blocks;
        return String.join("\n", shown) + (blocks.size() > 12 ? "\n... and " + (blocks.size() - 12) + " more" : "");
    }

    /** An empty annotation for a class only an annotation package mentions, so a compile can read what refers to it. */
    private static boolean stubAnnotation(String name, Path sources, Path classes, Path javac, List<String> done) throws IOException {
        if (name.contains("$") || done.contains(name) || ANNOTATIONS.stream().noneMatch(name::startsWith)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        String pkg = name.substring(0, dot);
        String simple = name.substring(dot + 1);
        Path file = sources.resolve(name.replace('.', '/') + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "package " + pkg + ";\n@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)\npublic @interface "
                + simple + " {\n}\n", StandardCharsets.UTF_8);
        Run run = run(javac, List.of("--release", "17", "-proc:none", "-d", classes.toString(), file.toString()), sources.resolve(simple + ".args"));
        if (run.exit() != 0) {
            return false;
        }
        done.add(name);
        return true;
    }

    private static Map<String, byte[]> read(Path classes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
                String rel = classes.relativize(p).toString().replace('\\', '/');
                out.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(p));
            }
        }
        return out;
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }
}
