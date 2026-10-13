package dev.woflo.mycelium.gradle;

import com.google.gson.GsonBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code myceliumBridgeLinkage}: every bridge's every variant, with each overlay, held to the published names of every
 * version its realization row covers (decision 0016, "Proof in Mycelium's gate"; {@link BridgeLinkage}). Registered only by
 * {@code dev.woflo.mycelium.repository}: it is Mycelium's own proof, run in this repository's {@code check}, before any mod
 * builds a bridge. A reference a version lacks, or a variant that does not compile against a version it claims, fails (L1);
 * a version whose name data could not be had, or has none, is reported and left unlinked (L2, decision 0017).
 */
@UntrackedTask(because = "it reads pinned game archives from the name cache, which are not Gradle inputs")
public abstract class BridgeLinkageTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getRoot();

    /** Where pinned name data is kept (and the games opened from it). */
    @Internal
    public abstract DirectoryProperty getCache();

    @Input
    public abstract Property<Boolean> getOffline();

    /**
     * The Java compiler to compile the bridges with: a toolchain's, as new as the newest Java the game runs on, since a compiler
     * cannot read class files newer than itself. Defaults to the running JVM's.
     */
    @Internal
    public abstract RegularFileProperty getJavac();

    @Internal
    public abstract DirectoryProperty getReportDir();

    @TaskAction
    public void run() throws IOException {
        Path root = getRoot().get().getAsFile().toPath();
        Path report = getReportDir().get().getAsFile().toPath();
        Files.createDirectories(report);
        NameCache cache = new NameCache(getCache().get().getAsFile().toPath(), getOffline().get());
        BridgeLinkage.Result result = BridgeLinkage.run(root, cache, report.resolve("work"),
                getJavac().isPresent() ? getJavac().get().getAsFile().toPath() : BridgeLinkage.runningJavac());
        Files.writeString(report.resolve("linkage.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result.report()) + "\n",
                StandardCharsets.UTF_8);
        Checks.Findings found = new Checks.Findings(Checks.Config.DEFAULTS);
        for (String finding : result.findings()) {
            found.add("L1", "*", finding);
        }
        for (String note : result.notes()) {
            found.add("L2", "*", note);
        }
        found.conclude(getLogger(), "bridge linkage: " + (result.findings().size()) + " group(s) do not link, " + result.notes().size()
                + " version(s) left unlinked");
        getLogger().lifecycle("Mycelium: every bridge variant links on the versions that have name data ({}); {} note(s)", report.resolve("linkage.json"),
                result.notes().size());
    }
}
