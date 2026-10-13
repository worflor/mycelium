package dev.woflo.mycelium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Chooses the contracts this project imports, at execution time, after whatever writes the main sources (a Stonecutter
 * swap) has run, so it reads the sources the project compiles. A value computed while the build is configured sees the
 * sources as they were before any generator ran (none at all, on a clean build). Writes one id per line to
 * {@link #getContractList()}; every other task reads the list from there. An explicit {@code contracts(...)} passes through
 * unchanged, and so does the verification project's empty list (it has no Java plugin: nothing imported, all verified).
 */
public abstract class DeriveContractsTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    @Input
    public abstract Property<String> getNamespace();

    /** The explicit {@code contracts(...)} (or {@code -Pmycelium.contracts}); non-empty means nothing is derived. */
    @Input
    public abstract ListProperty<String> getExplicit();

    /**
     * The bridges the mod asks for by name ({@code bridges { use(...) }}): exactly these are imported, whatever its sources reference.
     * The contracts they implement are imported with them.
     */
    @Input
    public abstract ListProperty<String> getUse();

    /** False in the verification project (no Java plugin): nothing is derived there. */
    @Input
    public abstract Property<Boolean> getMod();

    /**
     * The main source set's directories, as the compile task will read them. Not an input file collection: it holds
     * {@code myceliumImport}'s own output (a source directory too), which would make this task wait on the task that waits
     * on it. The ordering is declared with {@code dependsOn} instead, and the task keeps no up-to-date state.
     */
    @Internal
    public abstract ConfigurableFileCollection getMainSources();

    /** The pure layer's sources (they are checked in, no generator writes them). */
    @InputFiles
    public abstract ConfigurableFileCollection getPureSources();

    /** The directory every import writes under (models, bridges, conformance): a reference there is the import, not a use. */
    @Internal
    public abstract DirectoryProperty getGeneratedDir();

    @OutputFile
    public abstract RegularFileProperty getContractList();

    /**
     * The bridges to import, one slug per line: {@link #getUse()} when the mod names them, else the ones its own sources reference
     * ({@code <namespace>.bridges.<package>}). A contract's bridge is an offer the mod takes by using it, never one it gets for
     * importing the contract's model. An override and a skip are applied by {@code myceliumBridgeImport}.
     */
    @OutputFile
    public abstract RegularFileProperty getBridgeList();

    public DeriveContractsTask() {
        doNotTrackState("reads sources a generator writes without declaring them; a text scan is cheap, and the tasks that use the list compare its content");
    }

    /** The contracts of the bridges the mod takes, added to {@code ids} when they are not there (a bridge needs its contract's model). */
    static List<String> withBridgeContracts(java.io.File root, List<String> ids, List<String> bridges) {
        if (bridges.isEmpty()) {
            return ids;
        }
        List<String> out = new java.util.ArrayList<>(ids);
        try {
            for (String slug : bridges) {
                String contract = Bridges.require(root.toPath(), slug).contract();
                if (!out.contains(contract)) {
                    out.add(contract);
                }
            }
        } catch (IOException e) {
            throw new org.gradle.api.GradleException("Mycelium: cannot read the bridges: " + e.getMessage(), e);
        }
        return out;
    }

    @TaskAction
    public void run() throws IOException {
        List<String> explicit = getExplicit().get();
        MyceliumPlugin.Derived derived = getMod().get()
                ? MyceliumPlugin.derive(getRoot().get().getAsFile(), getMainSources().getFiles(), getPureSources().getFiles(),
                        getNamespace().get(), getGeneratedDir().get().getAsFile())
                : new MyceliumPlugin.Derived(List.of(), List.of());
        List<String> ids = !explicit.isEmpty() || !getMod().get() ? explicit : derived.contracts();
        List<String> bridges = getUse().get().isEmpty() ? derived.bridges() : getUse().get();
        ids = withBridgeContracts(getRoot().get().getAsFile(), ids, bridges);
        java.nio.file.Path out = getContractList().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", ids), StandardCharsets.UTF_8);
        Files.writeString(getBridgeList().get().getAsFile().toPath(), String.join("\n", bridges), StandardCharsets.UTF_8);
        getLogger().info("Mycelium: contracts {} ({}); bridges {} ({})", ids, !explicit.isEmpty() ? "declared" : getMod().get() ? "derived" : "all verified",
                bridges, getUse().get().isEmpty() ? "referenced" : "named");
    }
}
