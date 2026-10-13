package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code myceliumRecordProofs}, Mycelium's own root only: after every harness and every era gate has passed, records
 * {@code contracts/proofs.json} ({@link Proofs}) so a mod need not run them again. It depends on both, so it records only a
 * result that held.
 */
public abstract class ProofRecordTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** The Javas the era gates ran on (the verification project's {@code eras}). */
    @Input
    public abstract ListProperty<Integer> getEras();

    public ProofRecordTask() {
        // the record is a file in the repository, rewritten only when it changed: never up to date from Gradle's point of view
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        ContractRegistry registry = ContractRegistry.load(root.toFile());
        Map<String, String> contracts = new LinkedHashMap<>();
        for (ContractRegistry.Entry e : registry.all()) {
            contracts.put(e.id(), Proofs.fingerprint(root, registry, e));
        }
        Proofs.write(root, new Proofs.Recorded(List.copyOf(getEras().get()), contracts));
        getLogger().lifecycle("Mycelium: recorded the proof of {} contract(s) on Java {} in contracts/proofs.json", contracts.size(), getEras().get());
    }
}
