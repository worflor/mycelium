package dev.woflo.mycelium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.plugins.JvmToolchainsPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 *   <li>{@code myceliumPurity} / {@code myceliumPureCompile}: the artifact's pure layer, its laws and its suites.</li>
 *   <li>{@code myceliumEras}: every era gate ({@code myceliumEraJava<N>}): the contracts and the pure layer on each era's
 *       Java. The eras come from {@code minecraft} (the target's own era) or {@code eras(...)} (docs/eras.md).</li>
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
        ext.getDowngraderVersion().convention("2.0.1");
        ext.getShadePrefix().convention(ext.getNamespace().map(n -> n.replace('.', '_') + "_jvmdg"));
        ext.getPure().getRelease().convention(VerifyContractsTask.RELEASE);
        ext.getPure().getSuiteMain().convention("Run");

        // `-Pmycelium.contracts=a@1,b@1` overrides the selection for one invocation (useful while packages land).
        List<String> contractOverride = listProperty(project, "mycelium.contracts");
        if (!contractOverride.isEmpty()) {
            ext.getContracts().set(contractOverride);
        }
        // `-Pmycelium.suites=noise,rng` runs only those suites in the era gates (by area or bundle name).
        List<String> suites = listProperty(project, "mycelium.suites");

        Provider<String> fingerprint = project.provider(() -> {
            Path root = ext.getRoot().get().getAsFile().toPath();
            List<Path> dirs = new ArrayList<>();
            dirs.add(root.resolve("contracts/registry.json"));
            dirs.add(root.resolve("harness"));
            for (String line : Files.readAllLines(root.resolve("harness/sources.txt"), StandardCharsets.UTF_8)) {
                if (!line.isBlank() && !line.strip().startsWith("#")) {
                    dirs.add(root.resolve(line.strip()));
                }
            }
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
        Provider<Directory> pureBundle = project.getLayout().getBuildDirectory().dir("mycelium/pure/bundle");

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
            t.getMinecraft().set(ext.getMinecraft());
            t.getErasReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/eras"));
            t.getOutputDir().set(evidenceDir);
            t.dependsOn(verifyTask);
        });

        // The pure layer (docs/eras.md): its laws on the source, then compiled on its own into a bundle.
        TaskProvider<PurityTask> purityTask = project.getTasks().register("myceliumPurity", PurityTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Checks the pure layer mentions no platform (P1) and reads no clock or unseeded chance (P3).");
            t.getPureSources().from(ext.getPure().getSources());
            t.getForbidden().addAll(ext.getPure().getForbidden());
            t.getForbidden().addAll(ext.getIntegrations().map(list -> {
                List<String> apis = new ArrayList<>();
                for (String encoded : list) {
                    apis.addAll(IsolationTask.Integration.decode(encoded).apis());
                }
                return apis;
            }));
            t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/purity"));
            t.onlyIf(spec -> !ext.getPure().getSources().isEmpty());
        });
        TaskProvider<PureCompileTask> pureCompile = project.getTasks().register("myceliumPureCompile", PureCompileTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Compiles the pure layer, its suites and the Mycelium harness on their own, as written.");
            t.getRoot().set(ext.getRoot());
            t.getHarnessFingerprint().set(fingerprint);
            t.getSources().from(ext.getPure().getSources());
            t.getSources().from(importTask.flatMap(ImportContractsTask::getOutputDir));
            t.getTests().from(ext.getPure().getTests());
            t.getRelease().set(ext.getPure().getRelease());
            t.getSuiteMain().set(ext.getPure().getSuiteMain());
            t.getBundleName().set(ext.getArtifactId().map(a -> a + " pure layer"));
            t.getBundleDir().set(pureBundle);
            t.dependsOn(purityTask, importTask);
            t.onlyIf(spec -> !ext.getPure().getSources().isEmpty() && !ext.getPure().getTests().isEmpty());
        });

        // The era gates: JvmDowngrader (resolved only when a gate runs older bytecode) and a toolchain per era.
        project.getPluginManager().apply(JvmToolchainsPlugin.class);
        Configuration downgrader = project.getConfigurations().create("myceliumDowngrader", c -> {
            c.setCanBeConsumed(false);
            c.setTransitive(false);
            c.defaultDependencies(d -> d.add(project.getDependencies().create(
                    "xyz.wagyourtail.jvmdowngrader:jvmdowngrader:" + ext.getDowngraderVersion().get() + ":all")));
        });
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        TaskProvider<Task> erasTask = project.getTasks().register("myceliumEras", t -> {
            t.setGroup("mycelium");
            t.setDescription("Runs every era gate: the contracts and the pure layer on each era's Java (docs/eras.md).");
        });
        project.afterEvaluate(p -> {
            List<Integer> eras = ext.resolvedEras();
            if (eras.isEmpty()) {
                return;
            }
            if (eras.stream().anyMatch(java -> java < ext.getPure().getRelease().get() || java < VerifyContractsTask.RELEASE)) {
                // Scoped to the one group, and it changes no other repository (Loom seals its own once used).
                p.getRepositories().maven(m -> {
                    m.setName("JvmDowngrader (Mycelium eras)");
                    m.setUrl("https://maven.wagyourtail.xyz/releases");
                    m.content(c -> c.includeGroup("xyz.wagyourtail.jvmdowngrader"));
                });
            }
            for (Integer java : eras) {
                TaskProvider<EraTask> one = p.getTasks().register("myceliumEraJava" + java, EraTask.class, t -> {
                    t.setGroup("mycelium");
                    t.setDescription("Runs the contracts and the pure layer on Java " + java + " (stubs, linkage, suites).");
                    t.getJava().set(java);
                    t.getBundles().from(verifyBundles(p, ext, reportDir));
                    t.getBundles().from(pureBundle);
                    t.getSuites().set(suites);
                    t.getShadePrefix().set(ext.getShadePrefix());
                    t.getDowngrader().from(downgrader);
                    t.getLauncher().set(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(java))));
                    t.getReportDir().set(p.getLayout().getBuildDirectory().dir("mycelium/eras/java" + java));
                    t.dependsOn(verifyTask, pureCompile);
                });
                erasTask.configure(t -> t.dependsOn(one));
            }
        });
        evidenceTask.configure(t -> t.mustRunAfter(erasTask));

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            sourceSets.getByName("main").getJava().srcDir(importTask.flatMap(ImportContractsTask::getOutputDir));
            // Every other main source is platform: the pure layer may not reach into its packages (P1).
            purityTask.configure(t -> t.getPlatformSources().from(sourceSets.getByName("main").getJava().getSourceDirectories()
                    .filter(dir -> !dir.equals(generated.get().getAsFile()))));
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
            project.getTasks().named("compileJava", c -> c.dependsOn(isolationTask, purityTask));
            project.getTasks().named("check", check -> check.dependsOn(verifyTask, usageTask, isolationTask, purityTask, erasTask));
            evidenceTask.configure(t -> t.dependsOn(usageTask));
            project.getTasks().withType(Jar.class).configureEach(jar ->
                    jar.from(evidenceTask.flatMap(EvidenceTask::getOutputDir), spec -> spec.into("META-INF/mycelium")));
        });
    }

    /** The bundle directory of every contract this project verifies. */
    private static ConfigurableFileCollection verifyBundles(Project p, MyceliumExtension ext, Provider<Directory> reportDir) {
        return p.files(p.provider(() -> {
            List<Object> dirs = new ArrayList<>();
            if (!ext.getVerify().get()) {
                return dirs;
            }
            for (ContractRegistry.Entry e : ContractRegistry.load(ext.getRoot().get().getAsFile()).resolve(ext.getContracts().get())) {
                dirs.add(reportDir.get().dir(e.slug()));
            }
            return dirs;
        }));
    }

    private static List<String> listProperty(Project project, String name) {
        List<String> out = new ArrayList<>();
        Object value = project.findProperty(name);
        if (value != null) {
            for (String s : String.valueOf(value).split(",")) {
                if (!s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }
}
