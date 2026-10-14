package dev.woflo.mycelium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * What {@code myceliumPresentation} and {@code myceliumPresentationLock} do, apart from Gradle: read a consumer's
 * presentation sources, run {@code item.presentation@1}'s own compiler on them, and say what it produced. The compiler is
 * the contract's model, compiled from the Mycelium checkout with the models it requires (docs/decisions/0014) and called
 * with nothing but text, so the build and the contract's harness run the same code and this class holds no rule of its own.
 * The era table ({@code eras/minecraft.json}) says which realization the target version has.
 */
final class Presentation {
    /** The contract the item tasks compile with. */
    static final String CONTRACT = "item.presentation@1";

    /** The contract the block mosaics compile with (docs/decisions/0019). */
    static final String BLOCK_CONTRACT = "block.presentation@1";

    /** A mosaic's file name ends with this; every other {@code *.json} but the catalog and the lock is an item program. */
    static final String MOSAIC_SUFFIX = ".mosaic.json";

    private Presentation() {
    }

    /**
     * @param ok          nothing was refused
     * @param diagnostics every refusal, one text each (a missing lock lists the lines to add)
     * @param files       each file to write, by path relative to a resources root
     * @param lock        the lock as it should be (only {@link #complete}); {@code null} otherwise
     * @param added       the lock lines {@link #complete} appended
     * @param items       the items compiled
     * @param realization the realization the target version has
     */
    record Outcome(boolean ok, List<String> diagnostics, Map<String, String> files, String lock, List<String> added, int items,
                   String realization) {
    }

    /** How many programs {@code sources} holds: the {@code *.json} files below it but the catalog, the lock and the block mosaics. */
    static int programs(Path sources, Path components, Path lock) throws IOException {
        return read(sources, components, lock).programs.size();
    }

    /** How many block mosaics ({@code *.mosaic.json}) {@code sources} holds. */
    static int mosaics(Path sources, Path components, Path lock) throws IOException {
        return read(sources, components, lock).mosaics.size();
    }

    /** Compiles every program under {@code sources} for {@code minecraft}. */
    static Outcome compile(Path root, Path work, String minecraft, Path sources, Path components, Path lock) throws IOException {
        String realization = Eras.load(root.toFile()).presentationFor(minecraft);
        Inputs in = read(sources, components, lock);
        if (!in.problems.isEmpty()) {
            return new Outcome(false, in.problems, new TreeMap<>(), null, List.of(), 0, realization);
        }
        if (in.programs.isEmpty()) {
            return new Outcome(true, List.of(), new TreeMap<>(), null, List.of(), 0, realization);
        }
        JsonObject report = call(CONTRACT, "SigilTool", root, work, "compile",
                new Class<?>[] {String.class, String.class, Map.class, String.class, String.class},
                minecraft, realization, in.programs, in.components, in.lock);
        return outcome(report, realization, "items");
    }

    /**
     * Compiles every mosaic ({@code *.mosaic.json}) under {@code sources} for {@code minecraft}: the same sources, the same catalog,
     * the block era table's realization, no lock (a mosaic has no legacy codes).
     */
    static Outcome compileMosaics(Path root, Path work, String minecraft, Path sources, Path components, Path lock) throws IOException {
        String realization = Eras.load(root.toFile()).blockPresentationFor(minecraft);
        Inputs in = read(sources, components, lock);
        if (in.mosaics.isEmpty()) {
            return new Outcome(true, List.of(), new TreeMap<>(), null, List.of(), 0, realization);
        }
        if (!in.problems.isEmpty()) {
            return new Outcome(false, in.problems, new TreeMap<>(), null, List.of(), 0, realization);
        }
        JsonObject report = call(BLOCK_CONTRACT, "MosaicTool", root, work, "compile",
                new Class<?>[] {String.class, String.class, Map.class, String.class},
                minecraft, realization, in.mosaics, in.components);
        return outcome(report, realization, "blocks");
    }

    /** Appends to the lock the codes its programs lack, without writing it (the task does, in the consumer's sources). */
    static Outcome complete(Path root, Path work, Path sources, Path components, Path lock) throws IOException {
        Inputs in = read(sources, components, lock);
        if (!in.problems.isEmpty()) {
            return new Outcome(false, in.problems, new TreeMap<>(), null, List.of(), 0, "");
        }
        JsonObject report = call(CONTRACT, "SigilTool", root, work, "lock", new Class<?>[] {Map.class, String.class, String.class},
                in.programs, in.components, in.lock);
        return outcome(report, "", "items");
    }

    private static Outcome outcome(JsonObject report, String realization, String counted) {
        List<String> diagnostics = new ArrayList<>();
        for (JsonElement e : report.getAsJsonArray("diagnostics")) {
            JsonObject d = e.getAsJsonObject();
            diagnostics.add("[" + d.get("kind").getAsString() + "] " + d.get("where").getAsString() + ": " + d.get("message").getAsString());
        }
        Map<String, String> files = new TreeMap<>();
        report.getAsJsonObject("files").entrySet().forEach(f -> files.put(f.getKey(), f.getValue().getAsString()));
        List<String> added = new ArrayList<>();
        if (report.has("added")) {
            for (JsonElement e : report.getAsJsonArray("added")) {
                added.add(e.getAsJsonObject().get("item").getAsString() + " " + e.getAsJsonObject().get("line").getAsString());
            }
        }
        return new Outcome(report.get("ok").getAsBoolean(), diagnostics, files,
                report.has("lock") ? report.get("lock").getAsString() : null, added, report.getAsJsonArray(counted).size(), realization);
    }

    // --- reading the consumer's sources -------------------------------------------------------------------------------

    private record Inputs(Map<String, String> programs, Map<String, String> mosaics, String components, String lock, List<String> problems) {
    }

    private static Inputs read(Path sources, Path components, Path lock) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, String> programs = new TreeMap<>();
        Map<String, String> mosaics = new TreeMap<>();
        if (Files.isDirectory(sources)) {
            // every *.json below the sources, however deep (a mod groups its programs in folders), but the catalog and the lock; they are
            // told apart from a program by the file, not by how the path to it was written. A name ending in .mosaic.json is a block
            // mosaic, never an item program.
            Path catalog = components.toAbsolutePath().normalize();
            Path locked = lock.toAbsolutePath().normalize();
            Path base = sources.toAbsolutePath().normalize();
            try (Stream<Path> walk = Files.walk(base)) {
                for (Path p : (Iterable<Path>) walk.sorted()::iterator) {
                    String name = p.getFileName().toString();
                    if (Files.isRegularFile(p) && name.endsWith(MOSAIC_SUFFIX)) {
                        mosaics.put(base.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
                    } else if (Files.isRegularFile(p) && name.endsWith(".json") && !p.equals(catalog) && !p.equals(locked)) {
                        programs.put(base.relativize(p).toString().replace('\\', '/'), Files.readString(p, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        if (programs.isEmpty() && mosaics.isEmpty()) {
            problems.add("[SCHEMA] " + sources + ": no presentation programs (*.json) or block mosaics (*.mosaic.json) to compile");
        }
        String catalog = "";
        if (Files.isRegularFile(components)) {
            catalog = Files.readString(components, StandardCharsets.UTF_8);
        } else {
            problems.add("[CATALOG] " + components + ": the component catalog is missing. It states, for each component a program reads, whether"
                    + " it is network-visible and what its fields are; a consumer keeps it agreeing with its registry with ComponentCatalog.disagreements");
        }
        String lockText = Files.isRegularFile(lock) ? Files.readString(lock, StandardCharsets.UTF_8) : "{\"schema\": 1, \"items\": {}}";
        return new Inputs(programs, mosaics, catalog, lockText, problems);
    }

    // --- running the contract's own compiler ----------------------------------------------------------------------------

    private static JsonObject call(String contract, String tool, Path root, Path work, String method, Class<?>[] types, Object... args)
            throws IOException {
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        ContractRegistry.Entry entry = registry.require(contract);
        work = work.resolve(contract.replace('@', '-'));      // one compiled model per contract: they are rebuilt apart
        List<Path> models = new ArrayList<>();
        for (ContractRegistry.Entry e : registry.withRequirements(List.of(entry))) {
            models.add(root.resolve("contracts").resolve(e.path()).resolve("src").resolve(e.pkg().replace('.', '/')).resolve("model"));
        }
        Path classes = work.resolve("classes");
        String stamp = Fingerprint.ofDirectories(models);
        Path marker = work.resolve("model.sha256");
        if (!Files.isRegularFile(marker) || !Files.readString(marker, StandardCharsets.UTF_8).equals(stamp)) {
            Harness.deleteRecursively(work);
            Files.createDirectories(classes);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            if (javac == null) {
                throw new IllegalStateException("Mycelium: no system Java compiler; run Gradle on a JDK");
            }
            List<String> javacArgs = new ArrayList<>(List.of("--release", String.valueOf(VerifyContractsTask.RELEASE), "-d", classes.toString()));
            for (Path p : Harness.javaFiles(models)) {
                javacArgs.add(p.toString());
            }
            Path log = work.resolve("javac.log");
            int exit;
            try (OutputStream err = Files.newOutputStream(log)) {
                exit = javac.run(null, err, err, javacArgs.toArray(String[]::new));
            }
            if (exit != 0) {
                throw new IllegalStateException("Mycelium: " + contract + " does not compile (" + log + ")");
            }
            Files.writeString(marker, stamp, StandardCharsets.UTF_8);
        }
        try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            Method m = loader.loadClass(entry.pkg() + ".model." + tool).getMethod(method, types);
            return JsonParser.parseString((String) m.invoke(null, args)).getAsJsonObject();
        } catch (InvocationTargetException e) {
            throw new IllegalStateException("Mycelium: the presentation compiler failed: " + e.getCause(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Mycelium: " + contract + " does not offer the build tool (" + tool + "." + method + ")", e);
        }
    }

    /** The report as JSON, for the build's record of what ran. */
    static JsonObject summary(Outcome o, String minecraft) {
        return summary(o, minecraft, CONTRACT);
    }

    /** The report for one of the two contracts the task compiles with. */
    static JsonObject summary(Outcome o, String minecraft, String contract) {
        JsonObject r = new JsonObject();
        r.addProperty("schema", 1);
        r.addProperty("contract", contract);
        r.addProperty("minecraft", minecraft);
        r.addProperty("realization", o.realization());
        r.addProperty("ok", o.ok());
        r.addProperty("items", o.items());
        JsonArray files = new JsonArray();
        o.files().keySet().forEach(files::add);
        r.add("files", files);
        return r;
    }
}
