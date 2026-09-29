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
        ext.getSurfaceFailOnGrowth().convention(false);

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
            // the era and loader tables: a task whose output reads them (the manifest's era block, the Java a target
            // runs on, what the pure layer keeps out) re-runs when they change
            dirs.add(root.resolve("eras"));
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
        Provider<Directory> pureShipClasses = project.getLayout().getBuildDirectory().dir("mycelium/pure/ship/classes");
        Provider<Directory> pureShipReport = project.getLayout().getBuildDirectory().dir("mycelium/pure/ship/report");

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
            t.getPureShipReportDir().set(pureShipReport);
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
            t.getPlatformPackages().set(ext.getRoot().map(dir -> Loaders.load(dir.getAsFile()).platformPackages()));
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
            // Suites are not required: compiling alone proves purity in fact, and each era gate still checks the
            // bundle's stubs and linkage (P2). Requiring suites left a pure layer without them silently unchecked.
            t.onlyIf(spec -> !ext.getPure().getSources().isEmpty());
        });

        // Shipping downgraded jars (docs/eras.md, "Not yet built" 1): puts the pure layer's classes into this target's
        // jar, downgraded and shaded when the era needs it, or unmodified when it does not (docs/decisions/0006).
        TaskProvider<PureShipTask> pureShip = project.getTasks().register("myceliumPureShip", PureShipTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Puts the pure layer's classes (downgraded and shaded if this target's era needs it) into the jar.");
            t.getPureSources().from(ext.getPure().getSources());
            t.getImportedSources().from(importTask.flatMap(ImportContractsTask::getOutputDir));
            t.getSourceRelease().set(ext.getPure().getRelease());
            t.getTargetJava().set(ext.getMinecraft().map(ext::javaFor).orElse(Integer.MAX_VALUE));
            t.getShadePrefix().set(ext.getShadePrefix());
            t.getDowngraderVersion().set(ext.getDowngraderVersion());
            t.getBundleName().set(ext.getArtifactId().map(a -> a + " pure layer"));
            t.getEraReportDir().set(project.getLayout().getBuildDirectory()
                    .dir(ext.getMinecraft().map(mc -> "mycelium/eras/java" + ext.javaFor(mc)).orElse("mycelium/eras/none")));
            t.getClassesDir().set(pureShipClasses);
            t.getReportDir().set(pureShipReport);
            t.dependsOn(pureCompile);
            // Nothing to ship without a pure layer, and nothing to ship it *for* without a target era; either absent,
            // this is not a check with nothing to check (docs/admission.md) so much as a feature that does not apply.
            t.onlyIf(spec -> !ext.getPure().getSources().isEmpty() && ext.getMinecraft().isPresent());
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
            // Shipping needs its target's own era gate to have run, even when a consumer's `eras(...)` (readiness,
            // proving every era) omits it: the union guarantees myceliumEraJava<N> exists whenever myceliumPureShip
            // needs it, without a second, parallel mechanism for running the same gate.
            Integer targetJava = ext.getMinecraft().isPresent() ? ext.javaFor(ext.getMinecraft().get()) : null;
            boolean shipDowngrades = targetJava != null && !ext.getPure().getSources().isEmpty()
                    && targetJava < ext.getPure().getRelease().get();
            java.util.LinkedHashSet<Integer> eras = new java.util.LinkedHashSet<>(ext.resolvedEras());
            if (shipDowngrades) {
                eras.add(targetJava);
            }
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
            if (shipDowngrades) {
                // Only a downgrade reads the gate's jar. Without one, the gate's folder is not an input at all: a
                // consumer proving that era anyway must not make shipping depend on it.
                pureShip.configure(t -> {
                    t.dependsOn("myceliumEraJava" + targetJava);
                    t.getEraOutputs().from(t.getEraReportDir());
                });
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
            // The portability surface (docs/eras.md, "Not yet built" 2): never the generated imports, which are
            // proven pure already and would only report as zero-reference noise.
            TaskProvider<SurfaceTask> surfaceTask = project.getTasks().register("myceliumSurface", SurfaceTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Reports how much of this artifact's own sources touch platform packages outside its declared ports.");
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories()
                        .filter(dir -> !dir.equals(generated.get().getAsFile())));
                t.getPlatformPackages().set(ext.getRoot().map(dir -> Loaders.load(dir.getAsFile()).platformPackages()));
                t.getPorts().set(ext.getPorts());
                t.getFailOnGrowth().set(ext.getSurfaceFailOnGrowth());
                t.getBaselineFile().set(ext.getSurfaceBaseline());
                t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/surface"));
            });
            project.getTasks().named("compileJava", c -> c.dependsOn(isolationTask, purityTask));
            project.getTasks().named("check", check -> check.dependsOn(verifyTask, usageTask, isolationTask, purityTask, erasTask,
                    pureShip, surfaceTask));
            evidenceTask.configure(t -> t.dependsOn(usageTask, pureShip));
            project.getTasks().withType(Jar.class).configureEach(jar ->
                    jar.from(evidenceTask.flatMap(EvidenceTask::getOutputDir), spec -> spec.into("META-INF/mycelium")));

            // Downgrading the jar's own classes (docs/decisions/0007). Nothing in `check` or in a jar runs it: a consumer whose
            // port is written for a newer Java than its era runs puts this task's output where its loader's tooling takes the
            // jar (Loom's remapJar input, say), and no build that does not is changed.
            TaskProvider<Jar> jarTask = project.getTasks().named("jar", Jar.class);
            project.getTasks().register("myceliumDowngradeJar", DowngradeJarTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Downgrades the jar's own classes to the Java this target's era runs on, and proves it.");
                t.getInput().set(jarTask.flatMap(Jar::getArchiveFile));
                t.getClasspath().from(sourceSets.getByName("main").getCompileClasspath());
                t.getJava().set(ext.getMinecraft().map(ext::javaFor));
                t.getShadePrefix().set(ext.getShadePrefix().map(p -> p + "_ports"));
                t.getShippedShadePrefix().set(ext.getShadePrefix());
                t.getDowngrader().from(downgrader);
                t.getLauncher().set(ext.getMinecraft().flatMap(mc -> toolchains.launcherFor(
                        spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(ext.javaFor(mc))))));
                t.getOutput().set(project.getLayout().getBuildDirectory()
                        .file(jarTask.flatMap(Jar::getArchiveFileName).map(n -> "mycelium/downgrade/" + n)));
                t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/downgrade/report"));
            });

            // Shipping downgraded jars: the ship task's classes are the *only* path a pure package reaches this jar.
            // A consumer whose own main sourceSet also compiles the pure sources directly (as the substrate decision,
            // a downgrade — never the other way around, and never when no downgrade is needed (there, ship contributes
            // nothing, so nothing is excluded either; docs/decisions/0006).
            project.getTasks().withType(Jar.class).configureEach(jar -> {
                jar.dependsOn(pureShip);
                jar.from(pureShip.flatMap(PureShipTask::getClassesDir));
                Provider<java.io.File> shipClassesDir = pureShip.flatMap(t -> t.getClassesDir().getAsFile());
                // Exactly what ship put in makes way: a class from anywhere else whose entry ship also supplies, or whose
                // outermost class it supplies, is dropped. Decided per entry from ship's own output, so it holds however
                // the build is ordered.
                jar.exclude(element -> {
                    if (!ext.getMinecraft().isPresent() || ext.getPure().getSources().isEmpty()) {
                        return false;
                    }
                    int targetJava = ext.javaFor(ext.getMinecraft().get());
                    if (targetJava >= ext.getPure().getRelease().get()) {
                        return false; // no downgrade needed: ship contributed nothing, so nothing to make way for
                    }
                    java.nio.file.Path ship = shipClassesDir.get().toPath();
                    if (element.getFile().toPath().startsWith(ship)) {
                        return false; // never exclude the classes ship itself just added
                    }
                    return PureShipTask.makesWay(ship, element.getRelativePath().getPathString());
                });
            });
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
