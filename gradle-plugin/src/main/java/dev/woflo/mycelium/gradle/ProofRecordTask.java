package dev.woflo.mycelium.gradle;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code myceliumRecordProofs}, Mycelium's own root only: after every harness and every era gate has passed, records
 * {@code contracts/proofs.json} ({@link Proofs}) so a mod need not run them again.
 *
 * <p>It records what the reports say held, never what the registry lists. A proof is a statement about sources that ran, so a
 * contract is recorded only when {@code myceliumVerify}'s result for it is {@code pass} over the sources as they are now, and
 * every era gate ran its whole suite on it and passed. A run that was narrowed ({@code -Pmycelium.contracts},
 * {@code -Pmycelium.suites}, {@code -x myceliumVerify}) proves less than the registry holds, and a mod that took the record would
 * skip harnesses nobody ran: such a run records nothing and says why, leaving the committed record as it was (a stale fingerprint
 * only makes a mod run the harness itself).
 */
public abstract class ProofRecordTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** The Javas the era gates ran on (the verification project's {@code eras}). */
    @Input
    public abstract ListProperty<Integer> getEras();

    /** {@code myceliumVerify}'s {@code results.json}; absent when that task did not run. */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getVerifyResults();

    /** Each era gate's {@code era.json}, for the Javas in {@link #getEras()}. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getEraReports();

    public ProofRecordTask() {
        // the record is a file in the repository, rewritten only when it changed: never up to date from Gradle's point of view
        getOutputs().upToDateWhen(t -> false);
    }

    /** What the reports allow: the fingerprints to record, or why nothing is recorded. */
    record Decision(Map<String, String> contracts, List<String> problems) {
        boolean records() {
            return problems.isEmpty();
        }
    }

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        JsonObject verify = null;
        if (getVerifyResults().isPresent() && getVerifyResults().get().getAsFile().isFile()) {
            verify = JsonParser.parseString(Files.readString(getVerifyResults().get().getAsFile().toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<Integer, JsonObject> eras = new LinkedHashMap<>();
        for (File f : getEraReports().getFiles()) {
            if (f.isFile()) {
                JsonObject era = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                eras.put(era.get("java").getAsInt(), era);
            }
        }
        Decision decision = decide(root, registry, verify, eras, getEras().get());
        if (!decision.records()) {
            getLogger().lifecycle("Mycelium: nothing recorded in contracts/proofs.json (it is left as it was), because:\n  {}",
                    String.join("\n  ", decision.problems()));
            return;
        }
        Proofs.write(root, new Proofs.Recorded(List.copyOf(getEras().get()), decision.contracts()));
        getLogger().lifecycle("Mycelium: recorded the proof of {} contract(s) on Java {} in contracts/proofs.json", decision.contracts().size(), getEras().get());
    }

    /**
     * Whether the reports of this run prove every registered contract, and the fingerprints to record if they do. Apart from Gradle
     * so the self-tests can narrow a run every way a build can.
     *
     * @param verify {@code myceliumVerify}'s {@code results.json}, or null when it did not run
     * @param eras   each era gate's {@code era.json} by Java
     * @param needed the Javas the record claims
     */
    static Decision decide(Path root, ContractRegistry registry, JsonObject verify, Map<Integer, JsonObject> eras, List<Integer> needed) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, JsonObject> ran = new LinkedHashMap<>();
        if (verify == null) {
            problems.add("myceliumVerify wrote no results this build (-x myceliumVerify?), so no harness is known to have passed");
        } else {
            for (JsonElement e : verify.getAsJsonArray("results")) {
                ran.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        Map<String, String> recorded = new LinkedHashMap<>();
        for (ContractRegistry.Entry entry : registry.all()) {
            if (verify == null) {
                break;
            }
            JsonObject r = ran.get(entry.id());
            if (r == null) {
                problems.add(entry.id() + ": its harness was not run (-Pmycelium.contracts narrowed the run)");
                continue;
            }
            String status = r.has("status") ? r.get("status").getAsString() : "";
            if (!status.equals("pass")) {
                problems.add(entry.id() + ": its harness result is '" + status + "', not pass");
                continue;
            }
            String now = Proofs.fingerprint(root, registry, entry);
            if (!r.has("fingerprint") || !r.get("fingerprint").getAsString().equals(now)) {
                problems.add(entry.id() + ": its sources changed since the harness ran");
                continue;
            }
            recorded.put(entry.id(), now);
        }
        for (int java : needed) {
            JsonObject era = eras.get(java);
            if (era == null) {
                problems.add("the Java " + java + " era gate wrote no report this build (-x myceliumEraJava" + java + "?)");
                continue;
            }
            if (era.has("suites") && era.getAsJsonArray("suites").size() > 0) {
                problems.add("the Java " + java + " era gate ran only the suites " + era.get("suites") + " (-Pmycelium.suites narrowed it)");
                continue;
            }
            if (era.has("skipped") && era.getAsJsonArray("skipped").size() > 0) {
                problems.add("the Java " + java + " era gate skipped bundles " + era.get("skipped") + " (a harness did not run before it)");
                continue;
            }
            Map<String, JsonObject> bundles = new LinkedHashMap<>();
            for (JsonElement e : era.getAsJsonArray("results")) {
                bundles.put(e.getAsJsonObject().get("bundle").getAsString(), e.getAsJsonObject());
            }
            for (ContractRegistry.Entry entry : registry.all()) {
                JsonObject b = bundles.get(entry.id());
                if (b == null) {
                    problems.add(entry.id() + ": the Java " + java + " era gate did not run it");
                } else if (!b.has("status") || !b.get("status").getAsString().equals("pass")) {
                    problems.add(entry.id() + ": the Java " + java + " era gate did not pass it");
                } else if (!b.has("suitesTotal") || b.getAsJsonArray("suites").size() != b.get("suitesTotal").getAsInt()) {
                    problems.add(entry.id() + ": the Java " + java + " era gate ran only some of its suites");
                }
            }
        }
        return new Decision(recorded, problems);
    }
}
