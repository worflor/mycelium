package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Writes {@code evidence.json}: which contracts this artifact carries (with source hashes), the target
 * profile, and the design-time harness results. The manifest states what was run; it never claims
 * game-target support (DESIGN §11, scoped assurance statement).
 */
public abstract class EvidenceTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract Property<String> getSourceFingerprint();

    @Input
    public abstract ListProperty<String> getContracts();

    @Input
    public abstract Property<String> getNamespace();

    /** How the contract list was chosen: {@code declared}, {@code derived} from the mod's own sources, or {@code all-verified}. */
    @Input
    public abstract Property<String> getContractSelection();

    /** The mod's check levels and allowances ({@link Checks#decode}), recorded as written (docs/decisions/0017). */
    @Input
    public abstract ListProperty<String> getChecks();

    /** Where the text checks left their reports; read if present, as of the last time each task ran. */
    @Internal
    public abstract DirectoryProperty getPurityReportDir();

    @Internal
    public abstract DirectoryProperty getIsolationReportDir();

    @Internal
    public abstract DirectoryProperty getSurfaceReportDir();

    @Input
    public abstract Property<String> getTargetProfile();

    @Input
    public abstract Property<String> getArtifactId();

    @Input
    public abstract Property<String> getArtifactVersion();

    @Input
    @Optional
    public abstract Property<Boolean> getVerifyEnabled();

    /** The verify task's report dir, if verification ran. */
    @Internal
    public abstract DirectoryProperty getVerifyReportDir();

    /** The Minecraft version this artifact targets; its era is recorded (eras/minecraft.json). */
    @Input
    @Optional
    public abstract Property<String> getMinecraft();

    /** Where the era gates left {@code java<N>/era.json}. */
    @Internal
    public abstract DirectoryProperty getErasReportDir();

    /** The usage task's report dir, if it ran: whether the artifact's sources reference each contract. */
    @Internal
    public abstract DirectoryProperty getUsageReportDir();

    /** {@code myceliumRange}'s report, if the artifact declares a range (docs/decisions/0009). */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getRangeReport();

    /** {@code myceliumPureShip}'s report dir, if it ran: {@code ship.json} (docs/decisions/0006). */
    @Internal
    public abstract DirectoryProperty getPureShipReportDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        List<String> ids = getContracts().get();

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", 3);
        evidence.addProperty("generatedAt", Instant.now().toString());
        JsonObject artifact = new JsonObject();
        artifact.addProperty("id", getArtifactId().get());
        artifact.addProperty("version", getArtifactVersion().get());
        artifact.addProperty("targetProfile", getTargetProfile().get());
        evidence.add("artifact", artifact);
        evidence.addProperty("namespace", getNamespace().get());
        if (getMinecraft().isPresent()) {
            Eras.Era era = Eras.load(root.toFile()).of(getMinecraft().get());
            JsonObject e = new JsonObject();
            e.addProperty("minecraft", getMinecraft().get());
            e.addProperty("id", era.id());
            e.addProperty("java", era.java());
            JsonObject names = new JsonObject();
            names.addProperty("dev", era.names().dev());
            JsonObject runtime = new JsonObject();
            JsonArray loaders = new JsonArray();
            for (String loader : era.loaders()) {
                if (era.offers(loader, getMinecraft().get())) {
                    loaders.add(loader);
                    String name = era.names().runtimeFor(loader, getMinecraft().get());
                    if (name != null) {
                        runtime.addProperty(loader, name);
                    }
                }
            }
            names.add("runtime", runtime);
            e.add("names", names);
            e.add("loaders", loaders);
            evidence.add("era", e);
        }
        JsonObject range;
        if (getRangeReport().isPresent() && getRangeReport().get().getAsFile().isFile()) {
            range = JsonParser.parseString(Files.readString(getRangeReport().get().getAsFile().toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } else {
            range = new JsonObject();
            range.addProperty("checked", false);
            range.addProperty("why", "this artifact declares no range (mycelium { range { ... } })");
        }
        evidence.add("range", range);
        // Every check below says whether it ran: {"checked": true, ...} or {"checked": false, "why": ...}. A check that is
        // absent, or silent, would read the same as one that ran and held (docs/decisions/0004).
        Path erasDir = getErasReportDir().isPresent() ? getErasReportDir().get().getAsFile().toPath() : null;
        JsonObject eraGates = new JsonObject();
        JsonArray gates = new JsonArray();
        if (erasDir != null && Files.isDirectory(erasDir)) {
            try (var dirs = Files.list(erasDir)) {
                for (Path d : (Iterable<Path>) dirs.sorted(java.util.Comparator.comparingInt(EvidenceTask::eraNumber))::iterator) {
                    Path f = d.resolve("era.json");
                    if (!Files.isRegularFile(f)) {
                        continue;
                    }
                    JsonObject era = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject g = new JsonObject();
                    g.addProperty("java", era.get("java").getAsInt());
                    JsonArray bundles = new JsonArray();
                    for (var r : era.getAsJsonArray("results")) {
                        JsonObject ro = r.getAsJsonObject();
                        JsonObject b = new JsonObject();
                        b.addProperty("bundle", ro.get("bundle").getAsString());
                        b.addProperty("status", ro.has("status") ? ro.get("status").getAsString() : "unknown");
                        if (ro.has("bytecode")) {
                            b.addProperty("bytecode", ro.get("bytecode").getAsString());
                        }
                        bundles.add(b);
                    }
                    g.addProperty("checked", bundles.size());
                    if (era.has("expected")) {
                        g.addProperty("expected", era.get("expected").getAsInt());
                    }
                    if (era.has("skipped")) {
                        g.add("skipped", era.getAsJsonArray("skipped"));
                    }
                    g.add("bundles", bundles);
                    gates.add(g);
                }
            }
        }
        eraGates.addProperty("checked", !gates.isEmpty());
        if (gates.isEmpty()) {
            eraGates.addProperty("why", "no era gate ran in this build (none declared, or myceliumEras was not run)");
        }
        eraGates.add("gates", gates);
        evidence.add("eraGates", eraGates);

        // Shipping downgraded jars (docs/decisions/0006): source release, target Java, the downgrader version, what
        // was shaded, and that the era gate's stubs/linkage/behaviour checks ran on exactly these bytes — or, honestly,
        // that shipping did not run, or ran and found nothing to downgrade.
        JsonObject pureShip;
        Path shipFile = getPureShipReportDir().isPresent()
                ? getPureShipReportDir().get().getAsFile().toPath().resolve("ship.json") : null;
        if (shipFile != null && Files.isRegularFile(shipFile)) {
            pureShip = JsonParser.parseString(Files.readString(shipFile, StandardCharsets.UTF_8)).getAsJsonObject();
            pureShip.addProperty("checked", true);
        } else {
            pureShip = notChecked("myceliumPureShip did not run in this build (no pure layer declared, or no "
                    + "mycelium.minecraft target set)");
        }
        evidence.add("pureShip", pureShip);

        JsonObject harness = null;
        Path results = getVerifyReportDir().isPresent()
                ? getVerifyReportDir().get().getAsFile().toPath().resolve("results.json") : null;
        if (results != null && Files.isRegularFile(results)) {
            harness = JsonParser.parseString(Files.readString(results, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        JsonObject usage = null;
        Path usageFile = getUsageReportDir().isPresent()
                ? getUsageReportDir().get().getAsFile().toPath().resolve("usage.json") : null;
        if (usageFile != null && Files.isRegularFile(usageFile)) {
            usage = JsonParser.parseString(Files.readString(usageFile, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        JsonArray contracts = new JsonArray();
        for (String id : ids) {
            ContractRegistry.Entry entry = registry.require(id);
            JsonObject c = new JsonObject();
            c.addProperty("id", id);
            c.addProperty("path", entry.path());
            c.addProperty("status", entry.status());
            JsonArray requires = new JsonArray();
            entry.requires().forEach(requires::add);
            c.add("requires", requires);
            Path modelDir = root.resolve("contracts").resolve(entry.path()).resolve("src")
                    .resolve(entry.pkg().replace('.', '/')).resolve("model");
            c.addProperty("modelSha256", Fingerprint.ofDirectory(modelDir));
            if (harness != null) {
                for (var element : harness.getAsJsonArray("results")) {
                    JsonObject r = element.getAsJsonObject();
                    if (id.equals(r.get("id").getAsString())) {
                        JsonObject h = new JsonObject();
                        h.addProperty("checked", true);
                        h.addProperty("status", r.get("status").getAsString());
                        if (r.has("exit")) {
                            h.addProperty("exit", r.get("exit").getAsInt());
                        }
                        if (r.has("harness")) {
                            // what ran, as the harness itself reported it
                            JsonObject ran = r.getAsJsonObject("harness");
                            h.addProperty("assertions", ran.getAsJsonObject("reference").get("assertions").getAsInt());
                            h.addProperty("matrix", ran.getAsJsonArray("matrix").size());
                        }
                        c.add("harness", h);
                    }
                }
            }
            if (!c.has("harness")) {
                c.add("harness", notChecked(getVerifyEnabled().get()
                        ? "its harness did not run in this build" : "verification is off (mycelium { verify = false })"));
            }
            c.add("usage", usageOf(usage, id));
            contracts.add(c);
        }
        evidence.add("contracts", contracts);

        JsonObject selection = new JsonObject();
        selection.addProperty("mode", getContractSelection().get());
        selection.addProperty("why", switch (getContractSelection().get()) {
            case "declared" -> "mycelium { contracts(...) } named them";
            case "derived" -> "the contracts the mod's own sources reference under its namespace, with their requirements";
            default -> "a verification project: every contract is verified and none imported";
        });
        evidence.add("contractSelection", selection);

        // The checks: every level as configured (and the default it replaced), every allowance with its reason, and what the
        // text checks last found. A finding is shown with what the configuration made of it, an allowed one with its reason.
        JsonObject checks = Checks.decode(getChecks().get()).toJson();
        JsonObject reports = new JsonObject();
        for (var dir : List.of(java.util.Map.entry("purity", getPurityReportDir()), java.util.Map.entry("isolation", getIsolationReportDir()),
                java.util.Map.entry("surface", getSurfaceReportDir()))) {
            Path file = dir.getValue().isPresent() ? dir.getValue().get().getAsFile().toPath().resolve(dir.getKey() + ".json") : null;
            if (file != null && Files.isRegularFile(file)) {
                JsonObject r = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonObject one = new JsonObject();
                one.addProperty("checked", true);
                one.add("findings", r.has("findings") ? r.get("findings") : new JsonArray());
                if (dir.getKey().equals("isolation") && r.has("integrations")) {
                    one.add("integrations", r.get("integrations"));
                }
                reports.add(dir.getKey(), one);
            } else {
                reports.add(dir.getKey(), notChecked("myceliumPurity/Isolation/Surface did not run in this build"));
            }
        }
        checks.add("reports", reports);
        evidence.add("checks", checks);

        JsonObject claims = new JsonObject();
        claims.addProperty("designTimeOnly", true);
        claims.addProperty("note", "Harness results are design-time evidence for the reference model and rejection matrix. "
                + "They imply no game-target behavior. Game-target evidence lives in gametest reports for this target profile.");
        evidence.add("claims", claims);

        Path out = getOutputDir().get().getAsFile().toPath();
        Files.createDirectories(out);
        Files.writeString(out.resolve("evidence.json"),
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(evidence), StandardCharsets.UTF_8);
        getLogger().lifecycle("Mycelium: evidence manifest written for {} contract(s) ({})", ids.size(), getTargetProfile().get());
    }

    static JsonObject notChecked(String why) {
        JsonObject o = new JsonObject();
        o.addProperty("checked", false);
        o.addProperty("why", why);
        return o;
    }

    /** Whether the artifact's own sources reference a contract, or why that was not checked. */
    static JsonObject usageOf(JsonObject usage, String id) {
        if (usage == null) {
            return notChecked("myceliumUsage did not run in this build");
        }
        if (usage.has("checked") && !usage.get("checked").getAsBoolean()) {
            return notChecked(usage.has("why") ? usage.get("why").getAsString() : "usage was not checked");
        }
        for (var element : usage.getAsJsonArray("results")) {
            JsonObject u = element.getAsJsonObject();
            if (id.equals(u.get("id").getAsString())) {
                JsonObject o = new JsonObject();
                o.addProperty("checked", true);
                o.addProperty("referenced", u.get("referencingFiles").getAsInt() > 0);
                o.addProperty("referencingFiles", u.get("referencingFiles").getAsInt());
                return o;
            }
        }
        return notChecked("this contract was not among the contracts myceliumUsage checked");
    }

    /** {@code java8} → 8, so gates list in era order. */
    private static int eraNumber(Path dir) {
        String n = dir.getFileName().toString().replaceAll("\\D", "");
        return n.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(n);
    }
}
