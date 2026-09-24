package dev.woflo.mycelium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Mycelium development-time machine, Gradle face (DESIGN §12 runtime model):
 * <ul>
 *   <li>{@code myceliumImport}: copies selected contract {@code model} packages into
 *       {@code build/generated/sources/mycelium/java} under the consumer's namespace and adds them to {@code main}.</li>
 *   <li>{@code myceliumVerify}: compiles and runs each selected contract harness; wired into {@code check}.</li>
 *   <li>{@code myceliumEvidence}: writes {@code META-INF/mycelium/evidence.json} into every jar.</li>
 * </ul>
 * No shared Mycelium runtime is introduced; imported code is private to the artifact.
 */
public final class MyceliumPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        MyceliumExtension ext = project.getExtensions().create("mycelium", MyceliumExtension.class);
        ext.getNamespace().convention(project.provider(() -> {
            String group = String.valueOf(project.getGroup());
            return group.isBlank() ? "mycelium.imported" : group + ".mycelium";
        }));
        ext.getVerify().convention(true);
        ext.getFailOnUnused().convention(false);
        ext.getTargetProfile().convention(project.getName());
        ext.getArtifactId().convention(project.getName());
        ext.getArtifactVersion().convention(project.provider(() -> String.valueOf(project.getVersion())));

        // `-Pmycelium.contracts=a@1,b@1` overrides the selection for one invocation (useful while packages land).
        Object override = project.findProperty("mycelium.contracts");
        if (override != null && !String.valueOf(override).isBlank()) {
            List<String> ids = new ArrayList<>();
            for (String id : String.valueOf(override).split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
            ext.getContracts().set(ids);
        }

        Provider<String> fingerprint = project.provider(() -> {
            Path root = ext.getRoot().get().getAsFile().toPath();
            List<Path> dirs = new ArrayList<>();
            dirs.add(root.resolve("contracts/registry.json").getParent().resolve("registry.json"));
            dirs.add(root.resolve("harness/src"));
            ContractRegistry registry = ContractRegistry.load(root.toFile());
            for (ContractRegistry.Entry entry : registry.resolve(ext.getContracts().get())) {
                dirs.add(root.resolve("contracts").resolve(entry.path()).resolve("src"));
            }
            return Fingerprint.ofDirectories(dirs);
        });

        Provider<Directory> generated = project.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java");
        Provider<Directory> reportDir = project.getLayout().getBuildDirectory().dir("mycelium/verify");
        Provider<Directory> evidenceDir = project.getLayout().getBuildDirectory().dir("mycelium/evidence");
        Provider<Directory> usageDir = project.getLayout().getBuildDirectory().dir("mycelium/usage");

        TaskProvider<ImportContractsTask> importTask = project.getTasks().register("myceliumImport", ImportContractsTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Imports selected Mycelium contract models under the artifact-owned namespace.");
            t.getRoot().set(ext.getRoot());
            t.getContracts().set(ext.getContracts());
            t.getNamespace().set(ext.getNamespace());
            t.getSourceFingerprint().set(fingerprint);
            t.getOutputDir().set(generated);
        });

        TaskProvider<VerifyContractsTask> verifyTask = project.getTasks().register("myceliumVerify", VerifyContractsTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Compiles and runs each selected Mycelium contract harness (reference + rejection matrix).");
            t.getRoot().set(ext.getRoot());
            t.getContracts().set(ext.getContracts());
            t.getSourceFingerprint().set(fingerprint);
            t.getReportDir().set(reportDir);
            t.onlyIf(spec -> ext.getVerify().get());
        });

        TaskProvider<EvidenceTask> evidenceTask = project.getTasks().register("myceliumEvidence", EvidenceTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Writes the Mycelium evidence manifest for this artifact.");
            t.getRoot().set(ext.getRoot());
            t.getContracts().set(ext.getContracts());
            t.getNamespace().set(ext.getNamespace());
            t.getTargetProfile().set(ext.getTargetProfile());
            t.getArtifactId().set(ext.getArtifactId());
            t.getArtifactVersion().set(ext.getArtifactVersion());
            t.getVerifyEnabled().set(ext.getVerify());
            t.getSourceFingerprint().set(fingerprint);
            t.getVerifyReportDir().set(reportDir);
            t.getUsageReportDir().set(usageDir);
            t.getOutputDir().set(evidenceDir);
            t.dependsOn(verifyTask);
        });

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            sourceSets.getByName("main").getJava().srcDir(importTask.flatMap(ImportContractsTask::getOutputDir));
            TaskProvider<UsageTask> usageTask = project.getTasks().register("myceliumUsage", UsageTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Reports imported Mycelium contracts that this artifact's own sources never reference.");
                t.getRoot().set(ext.getRoot());
                t.getContracts().set(ext.getContracts());
                t.getNamespace().set(ext.getNamespace());
                t.getFailOnUnused().set(ext.getFailOnUnused());
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories());
                t.getImportedDir().set(generated);
                t.getReportDir().set(usageDir);
                t.dependsOn(importTask);
            });
            TaskProvider<IsolationTask> isolationTask = project.getTasks().register("myceliumIsolation", IsolationTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Checks that optional integrations never leak another mod's classes into the rest of the artifact.");
                t.getIntegrations().set(ext.getIntegrations());
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories());
                t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/isolation"));
            });
            project.getTasks().named("compileJava", c -> c.dependsOn(isolationTask));
            project.getTasks().named("check", check -> check.dependsOn(verifyTask, usageTask, isolationTask));
            evidenceTask.configure(t -> t.dependsOn(usageTask));
            project.getTasks().withType(Jar.class).configureEach(jar ->
                    jar.from(evidenceTask.flatMap(EvidenceTask::getOutputDir), spec -> spec.into("META-INF/mycelium")));
        });
    }
}
