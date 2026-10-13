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
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
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
 *   <li>{@code myceliumEvidence}: writes {@code META-INF/mycelium/evidence.json} into the selected artifact jars.</li>
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
        ext.project = project;
        // `myceliumDefaults { }` on the root project (dev.woflo.mycelium.defaults): written once, inherited by every project
        // that applies this plugin (docs/decisions/0017). A project's own setting always wins.
        Project rootProject = project.getRootProject();
        MyceliumExtension defaults = project == rootProject ? null
                : rootProject.getExtensions().findByType(MyceliumDefaultsPlugin.Defaults.class);
        conventions(project, ext, defaults);
        Provider<List<String>> checks = project.provider(() -> ext.getChecks().merged(defaults == null ? null : defaults.getChecks()));
        // A mod is a project with the Java plugin. A flag set when the plugin arrives, not a lookup of the project later:
        // nothing a task holds may reach the Project (the configuration cache).
        Property<Boolean> modFlag = project.getObjects().property(Boolean.class).convention(false);
        project.getPlugins().withType(JavaPlugin.class, applied -> modFlag.set(true));
        Provider<Boolean> isMod = modFlag;
        ConfigurableFileCollection derivationRoots = project.getObjects().fileCollection();
        // whatever writes the main sources (a Stonecutter swap) runs before anything reads them to find the contracts; not
        // myceliumImport and myceliumBridgeImport, which write into them
        // Stonecutter swaps the sources in a task of its own that nothing declares as the sources' producer, so it is named.
        org.gradle.api.tasks.TaskCollection<Task> stonecutter = project.getTasks().matching(task -> task.getName().equals("stonecutterGenerate"));
        java.util.concurrent.Callable<Object> sourceGenerators = () -> {
            List<Object> out = new ArrayList<>(derivationRoots.getBuildDependencies().getDependencies(null).stream()
                    .filter(task -> !task.getName().equals("myceliumImport") && !task.getName().equals("myceliumBridgeImport")).toList());
            out.addAll(stonecutter);
            return out;
        };
        Provider<String> verifyProperty = project.getProviders().gradleProperty("mycelium.verify").orElse("");
        Provider<Directory> generated = project.getLayout().getBuildDirectory().dir("generated/sources/mycelium/java");
        // everything Mycelium imports (models, bridges, conformance cases) is written under this one directory: the text scans
        // that look for the mod's own code never read below it
        Provider<Directory> importedRoot = project.getLayout().getBuildDirectory().dir("generated/sources/mycelium");
        Provider<Directory> bridgeSources = project.getLayout().getBuildDirectory().dir("generated/sources/mycelium/bridges/java");
        Provider<Directory> conformanceSources = project.getLayout().getBuildDirectory().dir("generated/sources/mycelium/conformance/java");
        Provider<Directory> conformanceResources = project.getLayout().getBuildDirectory().dir("generated/resources/mycelium/conformance");
        // The contracts this project imports: the explicit `contracts(...)` if given; in a mod (the Java plugin applied) what the
        // compiled sources reference under the namespace, with their requirements; in the verification project (no Java plugin),
        // none imported and every contract verified. Chosen when the build runs, by a task that waits for whatever writes the
        // sources: a value computed while the build is
        // configured sees the sources before any generator ran (a clean Stonecutter build has none yet).
        TaskProvider<DeriveContractsTask> deriveTask = project.getTasks().register("myceliumDeriveContracts", DeriveContractsTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Chooses the contracts to import: the explicit list, else the ones the compiled sources reference.");
            t.getRoot().set(ext.getRoot());
            t.getNamespace().set(ext.getNamespace());
            t.getExplicit().set(ext.getContracts());
            t.getMod().set(isMod);
            t.getMainSources().from(derivationRoots);
            t.getPureSources().from(ext.getPure().getSources());
            t.getGeneratedDir().set(importedRoot);
            t.getUse().set(ext.getBridges().getUse());
            t.getContractList().set(project.getLayout().getBuildDirectory().file("mycelium/contracts.txt"));
            t.getBridgeList().set(project.getLayout().getBuildDirectory().file("mycelium/bridges.txt"));
            t.dependsOn(sourceGenerators);
        });
        Provider<List<String>> contracts = deriveTask.flatMap(DeriveContractsTask::getContractList).map(MyceliumPlugin::lines);
        // the bridges the mod takes: the ones it names, else the ones its own sources reference
        Provider<List<String>> chosenBridges = deriveTask.flatMap(DeriveContractsTask::getBridgeList).map(MyceliumPlugin::lines);
        Provider<String> contractMode = project.provider(() -> !ext.getContracts().get().isEmpty() ? "declared"
                : isMod.get() ? "derived" : "all-verified");

        // `-Pmycelium.contracts=a@1,b@1` overrides the selection for one invocation (useful while packages land).
        List<String> contractOverride = listProperty(project, "mycelium.contracts");
        if (!contractOverride.isEmpty()) {
            ext.getContracts().set(contractOverride);
        }
        // `-Pmycelium.suites=noise,rng` runs only those suites in the era gates (by area or bundle name).
        List<String> suites = listProperty(project, "mycelium.suites");

        Provider<String> fingerprint = contracts.map(ids -> {
            Path root = ext.getRoot().get().getAsFile().toPath();
            List<Path> dirs = new ArrayList<>();
            dirs.add(root.resolve("contracts/registry.json"));
            dirs.add(root.resolve("harness"));
            // the era and loader tables: a task whose output reads them (the manifest's era block, the Java a target
            // runs on, what the pure layer keeps out) re-runs when they change
            dirs.add(root.resolve("eras"));
            List<String> lines;
            try {
                lines = Files.readAllLines(root.resolve("harness/sources.txt"), StandardCharsets.UTF_8);
            } catch (java.io.IOException x) {
                throw new org.gradle.api.GradleException("Mycelium: cannot read harness/sources.txt: " + x.getMessage(), x);
            }
            for (String line : lines) {
                if (!line.isBlank() && !line.strip().startsWith("#")) {
                    dirs.add(root.resolve(line.strip()));
                }
            }
            ContractRegistry registry = ContractRegistry.load(root.toFile());
            if (!ids.isEmpty() || !isMod.get()) {
                for (ContractRegistry.Entry entry : registry.withRequirements(registry.resolve(ids))) {
                    dirs.add(root.resolve("contracts").resolve(entry.path()).resolve("src"));
                }
            }
            return Fingerprint.ofDirectories(dirs);
        });

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
            t.getContracts().set(contracts);
            // the contracts are read from the main sources, which a source generator (Stonecutter) may write first
            t.dependsOn(sourceGenerators);
            t.getNamespace().set(ext.getNamespace());
            t.getSourceFingerprint().set(fingerprint);
            t.getOutputDir().set(generated);
        });

        // Bridges (docs/decisions/0016): the native half of the divides the imported contracts cross, as source for this target.
        Provider<Directory> bridgesReport = project.getLayout().getBuildDirectory().dir("mycelium/bridges");
        TaskProvider<ImportBridgesTask> bridgeImportTask = project.getTasks().register("myceliumBridgeImport", ImportBridgesTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Imports the bridges the imported contracts bring, for the target Minecraft version, under the artifact-owned namespace.");
            t.getRoot().set(ext.getRoot());
            t.getBridges().set(chosenBridges);
            t.getNamespace().set(ext.getNamespace());
            t.getMinecraft().set(ext.getMinecraft());
            t.getLoader().set(ext.getLoader());
            t.getSkip().set(ext.getBridges().getSkip());
            t.getOverrides().set(ext.getBridges().getOverrides());
            t.getConformance().set(ext.getBridges().getConformance());
            // tracked as files when the build runs: a fingerprint computed here reads the checkout while the build is configured, which only
            // works while Gradle tracks that read (and then discards the whole configuration on every edit)
            t.getChoiceSources().from(ext.getRoot().map(dir -> dir.dir("bridges")), ext.getRoot().map(dir -> dir.dir("eras")),
                    ext.getRoot().map(dir -> dir.file("contracts/registry.json")));
            t.getOutputDir().set(bridgeSources);
            t.getConformanceDir().set(conformanceSources);
            t.getConformanceResourcesDir().set(conformanceResources);
            t.getReportFile().set(bridgesReport.map(d -> d.file("import.json")));
            t.dependsOn(sourceGenerators);
            // a verification project has no sources to add them to
            t.onlyIf(spec -> isMod.get());
        });

        TaskProvider<VerifyContractsTask> verifyTask = project.getTasks().register("myceliumVerify", VerifyContractsTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Compiles and runs each selected Mycelium contract harness (reference + rejection matrix).");
            t.getRoot().set(ext.getRoot());
            t.getContracts().set(contracts);
            t.dependsOn(sourceGenerators);
            t.getSourceFingerprint().set(fingerprint);
            t.getReportDir().set(reportDir);
            // Mycelium's own check already ran each harness (contracts/proofs.json): a mod re-runs one only when its imported
            // sources differ from what was proved, or when asked (-Pmycelium.verify=always). The verification project runs all.
            t.getProofMode().set(isMod.zip(verifyProperty, (mod, verify) -> !mod ? "off" : "always".equals(verify) ? "always" : "auto"));
            t.getProofsFile().from(ext.getRoot().file("contracts/proofs.json"));
            t.getNeededEras().set(project.provider(() -> neededEras(ext)));
            // a mod that imports nothing has nothing to verify (an empty selection verifies everything only in the verification project)
            t.onlyIf(spec -> ext.getVerify().get() && (!contracts.get().isEmpty() || !isMod.get()));
        });

        TaskProvider<EvidenceTask> evidenceTask = project.getTasks().register("myceliumEvidence", EvidenceTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Writes the Mycelium evidence manifest for this artifact.");
            t.getRoot().set(ext.getRoot());
            t.getContracts().set(contracts);
            t.getContractSelection().set(contractMode);
            t.getChecks().set(checks);
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
            t.getPurityReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/purity"));
            t.getIsolationReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/isolation"));
            t.getSurfaceReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/surface"));
            t.getOutputDir().set(evidenceDir);
            t.dependsOn(verifyTask, sourceGenerators);
        });

        TaskProvider<CapsuleTask> capsuleTask = project.getTasks().register("myceliumCapsule", CapsuleTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Checks capsule class ceilings and final runtime class/member linkage against declared artifacts.");
            t.getArchive().set(ext.getCapsule().getArchive());
            t.getOuterJava().set(ext.getCapsule().getOuterJava());
            t.getRuntimeArtifacts().from(ext.getCapsule().getRuntimeArtifacts());
            t.getMappings().set(ext.getCapsule().getMappings());
            t.getMappingFrom().set(ext.getCapsule().getMappingFrom());
            t.getMappingTo().set(ext.getCapsule().getMappingTo());
            t.getMappedPrefixes().set(ext.getCapsule().getMappedPrefixes());
            t.getNestedJars().set(ext.getCapsule().getNestedJars());
            t.getReportFile().set(project.getLayout().getBuildDirectory().file("mycelium/capsule/capsule.json"));
            t.onlyIf(spec -> ext.getCapsule().getArchive().isPresent());
        });

        // Item presentation (docs/decisions/0012): the consumer's programs compiled for the target's era into generated resources,
        // and the lock of legacy codes completed on request. The layout is a convention; the era table chooses the mechanism.
        PresentationSpec presentation = ext.getPresentation();
        // a multi-project build (Stonecutter) keeps the programs once, beside the root: `myceliumDefaults { presentation { sources = ... } }`
        Provider<Directory> ownSources = project.provider(() -> project.getLayout().getProjectDirectory().dir("src/main/presentation"));
        presentation.getSources().convention(defaults == null ? ownSources : defaults.getPresentation().getSources().orElse(ownSources));
        presentation.getComponents().convention(defaults == null ? presentation.getSources().file("components.json")
                : defaults.getPresentation().getComponents().orElse(presentation.getSources().file("components.json")));
        presentation.getLock().convention(defaults == null ? presentation.getSources().file("presentation.lock.json")
                : defaults.getPresentation().getLock().orElse(presentation.getSources().file("presentation.lock.json")));
        Provider<Directory> presentationOut = project.getLayout().getBuildDirectory().dir("generated/resources/mycelium/presentation");
        // the compiled model, kept between runs: one directory for the build's task and one for the lock's, apart from the task's outputs
        Provider<Directory> presentationWork = project.getLayout().getBuildDirectory().dir("mycelium/presentation-work/model");
        Provider<Directory> presentationLockWork = project.getLayout().getBuildDirectory().dir("mycelium/presentation-lock-work/model");
        TaskProvider<PresentationTask> presentationTask = project.getTasks().register("myceliumPresentation", PresentationTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Compiles the item presentation programs for the target Minecraft version into its native artefacts and the plan the adapter reads.");
            t.getRoot().set(ext.getRoot());
            t.getMinecraft().set(ext.getMinecraft());
            // the compiler is item.presentation@1's models (and what they require): tracked as files when the build runs, not
            // computed while it is configured
            t.getCompilerSources().from(ext.getRoot().map(dir -> dir.file("contracts/registry.json")), ext.getRoot().map(dir -> dir.dir("eras")),
                    ext.getRoot().map(dir -> dir.dir("contracts").getAsFileTree().matching(pattern -> pattern.include("**/model/**/*.java"))));
            t.getChecks().set(checks);
            t.getSources().set(presentation.getSources());
            t.getComponents().set(presentation.getComponents());
            t.getLock().set(presentation.getLock());
            t.getSourceFiles().from(presentation.getSources().map(d -> d.getAsFileTree()));
            t.getCatalogFiles().from(presentation.getComponents(), presentation.getLock());
            t.getWorkDir().set(presentationWork);
            t.getOutputDir().set(presentationOut);
            t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/presentation"));
            // a build with no presentation sources has nothing to compile, and is not made to have any
            t.onlyIf(spec -> presentation.getSources().get().getAsFile().isDirectory());
        });
        project.getTasks().register("myceliumPresentationLock", PresentationLockTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Appends the legacy codes the item presentation programs need to the committed lock; never part of a build.");
            t.getRoot().set(ext.getRoot());
            t.getSources().set(presentation.getSources());
            t.getComponents().set(presentation.getComponents());
            t.getLock().set(presentation.getLock());
            t.getWorkDir().set(presentationLockWork);
        });

        // The pure layer (docs/eras.md): its laws on the source, then compiled on its own into a bundle.
        TaskProvider<PurityTask> purityTask = project.getTasks().register("myceliumPurity", PurityTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Checks the pure layer mentions no platform (P1) and reads no clock or unseeded chance (P3).");
            t.getPureSources().from(ext.getPure().getSources());
            t.getPlatformPackages().set(ext.getRoot().map(dir -> Loaders.load(dir.getAsFile()).platformPackages()));
            t.getChecks().set(checks);
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
            t.getStrict().set(ext.getPure().getStrict());
            t.getBundleName().set(ext.getArtifactId().map(a -> a + " pure layer"));
            t.getBundleDir().set(pureBundle);
            // the text checks explain; compiling alone is the proof, so a purity finding never keeps it from running
            t.dependsOn(importTask);
            t.mustRunAfter(purityTask);
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
        Configuration privateLibraries = project.getConfigurations().create("myceliumPrivateLibraries", c -> {
            c.setCanBeConsumed(false);
            c.setTransitive(true);
        });
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
                    t.getBundles().from(verifyBundles(p.files(), ext, reportDir, contracts, isMod, verifyProperty));
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
        evidenceTask.configure(t -> t.mustRunAfter(erasTask, purityTask));

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
            project.getConfigurations().named("implementation", configuration -> configuration.extendsFrom(privateLibraries));
            project.afterEvaluate(p -> p.getTasks().withType(JavaCompile.class).configureEach(compile ->
                    compile.setClasspath(privateLibraries.plus(compile.getClasspath()))));
            SourceSetContainer sourceSets = project.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            derivationRoots.from(sourceSets.getByName("main").getJava().getSourceDirectories());
            sourceSets.getByName("main").getJava().srcDir(importTask.flatMap(ImportContractsTask::getOutputDir));
            sourceSets.getByName("main").getJava().srcDir(bridgeImportTask.flatMap(ImportBridgesTask::getOutputDir));
            // the compiled presentation joins the resources, and the build fails before packaging if the programs break a law
            sourceSets.getByName("main").getResources().srcDir(presentationTask.flatMap(PresentationTask::getOutputDir));
            project.getTasks().named("processResources", t -> t.dependsOn(presentationTask));
            // Every other main source is platform: the pure layer may not reach into its packages (P1).
            purityTask.configure(t -> t.getPlatformSources().from(sourceSets.getByName("main").getJava().getSourceDirectories()
                    .filter(dir -> !isImported(dir, importedRoot.get().getAsFile()))));
            TaskProvider<UsageTask> usageTask = project.getTasks().register("myceliumUsage", UsageTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Reports imported Mycelium contracts that this artifact's own sources never reference.");
                t.getRoot().set(ext.getRoot());
                t.getContracts().set(contracts);
                t.getNamespace().set(ext.getNamespace());
                t.getFailOnUnused().set(ext.getFailOnUnused());
                t.getChecks().set(checks);
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories());
                t.getSources().from(ext.getPure().getSources());
                t.getImportedDir().set(importedRoot);
                t.getReportDir().set(usageDir);
                t.dependsOn(importTask);
            });
            TaskProvider<IsolationTask> isolationTask = project.getTasks().register("myceliumIsolation", IsolationTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Checks that optional integrations never leak another mod's classes into the rest of the artifact.");
                t.getIntegrations().set(ext.getIntegrations());
                t.getChecks().set(checks);
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories());
                t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/isolation"));
            });
            TaskProvider<PackMetaTask> packMetaTask = project.getTasks().register("myceliumPackMeta", PackMetaTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Writes this target's pack.mcmeta from the era table's pack formats (docs/decisions/0017).");
                t.getRoot().set(ext.getRoot());
                t.getMinecraft().set(ext.getMinecraft());
                t.getPackDescription().set(ext.getPackMeta().getDescription().orElse(ext.getArtifactId().map(id -> id + " resources")));
                t.getEraTable().set(ext.getRoot().map(dir -> dir.file("eras/minecraft.json")));
                t.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/resources/mycelium/pack"));
            });
            // The portability surface (docs/eras.md, "Not yet built" 2): never the generated imports, which are
            // proven pure already and would only report as zero-reference noise.
            TaskProvider<SurfaceTask> surfaceTask = project.getTasks().register("myceliumSurface", SurfaceTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Reports how much of this artifact's own sources touch platform packages outside its declared ports.");
                t.getSources().from(sourceSets.getByName("main").getJava().getSourceDirectories()
                        .filter(dir -> !isImported(dir, importedRoot.get().getAsFile())));
                t.getPlatformPackages().set(ext.getRoot().map(dir -> Loaders.load(dir.getAsFile()).platformPackages()));
                t.getPorts().set(ext.getPorts());
                t.getFailOnGrowth().set(ext.getSurfaceFailOnGrowth());
                t.getChecks().set(checks);
                t.onlyIf(spec -> Checks.decode(checks.get()).level("S1") != Checks.Level.OFF);
                t.getBaselineFile().set(ext.getSurfaceBaseline());
                t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/surface"));
            });
            // The conformance cases join the game-test sources when the mod asks (bridges { conformance() }): the gametest source set if
            // the project has one, else main, where a mod that keeps its game tests beside its code has them.
            project.afterEvaluate(p -> {
                if (!ext.getBridges().getConformance().getOrElse(false)) {
                    return;
                }
                String named = ext.getBridges().getConformanceSourceSet().getOrElse("");
                org.gradle.api.tasks.SourceSet target = named.isEmpty()
                        ? (sourceSets.findByName("gametest") != null ? sourceSets.getByName("gametest") : sourceSets.getByName("main"))
                        : sourceSets.findByName(named);
                if (target == null) {
                    throw new org.gradle.api.GradleException("Mycelium bridges: bridges { conformance(\"" + named + "\") } names a source set this project does not have ("
                            + sourceSets.getNames() + ")");
                }
                target.getJava().srcDir(bridgeImportTask.flatMap(ImportBridgesTask::getConformanceDir));
                target.getResources().srcDir(bridgeImportTask.flatMap(ImportBridgesTask::getConformanceResourcesDir));
            });
            // What can be said of the bridges once the classes are compiled: an override implements the port (B1), a bridge the game
            // cannot map on this version was not imported (B2). The evidence manifest carries the choice and the result.
            TaskProvider<BridgeCheckTask> bridgeCheckTask = project.getTasks().register("myceliumBridges", BridgeCheckTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Holds the bridges' overrides to their ports and reports the variant each bridge drew for this target.");
                t.getRoot().set(ext.getRoot());
                t.getNamespace().set(ext.getNamespace());
                t.getChecks().set(checks);
                t.getImportReport().set(bridgeImportTask.flatMap(ImportBridgesTask::getReportFile));
                t.getClasses().from(sourceSets.getByName("main").getOutput().getClassesDirs());
                // an override may live in another source set (a client one); look there when it was built, never build it for this
                t.getOtherClasses().from(project.provider(() -> sourceSets.stream().filter(set -> !set.getName().equals("main"))
                        .flatMap(set -> set.getOutput().getClassesDirs().getFiles().stream()).toList()));
                // ordering, not a dependency: when another source set is built in this run it is built first
                t.mustRunAfter(project.provider(() -> sourceSets.stream().filter(set -> !set.getName().equals("main"))
                        .map(set -> set.getOutput().getClassesDirs()).toList()));
                t.getReportDir().set(bridgesReport);
            });
            evidenceTask.configure(t -> {
                t.getBridgesReportDir().set(bridgesReport);
                t.dependsOn(bridgeCheckTask);
            });
            // The range gate (docs/decisions/0009): only for an artifact that declares one. It reads the processed resources, which
            // do not depend on the jar, so the evidence it feeds can go into the jar. It also runs after compileJava because
            // ForgeGradle (Forge 1.21 and later) writes classes and resources into one directory.
            project.afterEvaluate(p -> {
                if (!ext.getRange().isDeclared()) {
                    return;
                }
                ArtifactLedger.Artifact claim = ext.getRange().artifact();
                TaskProvider<RangeTask> rangeTask = p.getTasks().register("myceliumRange", RangeTask.class, t -> {
                    t.setGroup("mycelium");
                    t.setDescription("Holds the artifact's claimed Minecraft range to the era table, its proofs and its loader's metadata.");
                    t.getRoot().set(ext.getRoot());
                    t.getDeclaration().set(claim.toJson().toString());
                    t.getSourceFingerprint().set(fingerprint);
                    t.getChecks().set(checks);
                    t.getLinkageDeclared().set(ext.getRange().isLinkageDeclared());
                    t.getResources().from(sourceSets.getByName("main").getOutput().getResourcesDir());
                    t.getResourcesDir().set(sourceSets.getByName("main").getOutput().getResourcesDir());
                    t.getReportFile().set(p.getLayout().getBuildDirectory().file("mycelium/range/range.json"));
                    t.dependsOn(p.getTasks().named("processResources"), p.getTasks().named("compileJava"));
                });
                p.getTasks().named("check", check -> check.dependsOn(rangeTask));
                evidenceTask.configure(t -> {
                    t.getRangeReport().set(rangeTask.flatMap(RangeTask::getReportFile));
                    t.dependsOn(rangeTask);
                });
                // Linkage (docs/decisions/0010) reads the finished artifact, so it cannot be inside the manifest that goes into it:
                // it runs after the artifact exists and writes its own report. Only a range that declares it is linked.
                if (ext.getRange().isLinkageDeclared()) {
                    LinkageSpec spec = ext.getRange().getLinkage();
                    Provider<org.gradle.api.file.RegularFile> shipped = ext.getCapsule().getArchive().isPresent()
                            ? ext.getCapsule().getArchive() : p.getTasks().named("jar", Jar.class).flatMap(Jar::getArchiveFile);
                    spec.getArchive().convention(shipped);
                    spec.getNames().convention(ext.getRoot().file("eras/names.json"));
                    spec.getCache().convention(p.getLayout().dir(p.provider(() -> namesCache(p))));
                    if (ext.getCapsule().getArchive().isPresent()) {
                        spec.getNestedJars().convention(p.provider(() -> ext.getCapsule().getNestedJars().get().stream()
                                .map(encoded -> encoded.substring(0, encoded.lastIndexOf('|'))).toList()));
                    }
                    TaskProvider<LinkageTask> linkageTask = p.getTasks().register("myceliumLinkage", LinkageTask.class, t -> {
                        t.setGroup("mycelium");
                        t.setDescription("Holds the finished artifact to the game's published names on every version of its range that has name data.");
                        t.getRoot().set(ext.getRoot());
                        t.getDeclaration().set(claim.toJson().toString());
                        t.getSourceFingerprint().set(fingerprint);
                        t.getRangeReport().set(rangeTask.flatMap(RangeTask::getReportFile));
                        t.getChecks().set(checks);
                        t.getArchive().set(spec.getArchive());
                        t.getServerRuntimeArchive().set(spec.getServerRuntimeArchive());
                        t.getServerRuntimeNamespace().set(spec.getServerRuntimeNamespace());
                        t.getServerRuntimeOwns().set(spec.getServerRuntimeOwns());
                        if (ext.getMinecraft().isPresent()) {
                            t.getMinecraft().set(ext.getMinecraft());
                        }
                        t.getNestedJars().set(spec.getNestedJars());
                        t.getNamespace().set(spec.getNamespace());
                        t.getExcluded().from(spec.getExclude());
                        t.getNames().set(spec.getNames());
                        t.getCache().set(spec.getCache());
                        t.getOffline().set(p.getGradle().getStartParameter().isOffline());
                        if (ext.getCapsule().getArchive().isPresent()) {
                            t.getMinecraft().set(ext.getMinecraft());
                            t.getCapsuleReport().set(capsuleTask.flatMap(CapsuleTask::getReportFile));
                            t.dependsOn(capsuleTask);
                        }
                        t.getReportFile().set(p.getLayout().getBuildDirectory().file("mycelium/linkage/linkage.json"));
                        t.dependsOn(rangeTask);
                    });
                    p.getTasks().named("check", check -> check.dependsOn(linkageTask));
                }
            });
            // Nothing here blocks compilation (docs/decisions/0017): the text checks run in `check`; compiling, compiling the pure
            // layer alone and the linkage gate are the proofs, and the text explains them.
            evidenceTask.configure(t -> t.mustRunAfter(isolationTask, surfaceTask));
            project.getTasks().named("check", check -> check.dependsOn(verifyTask, usageTask, isolationTask, purityTask, erasTask,
                    pureShip, surfaceTask, capsuleTask, bridgeCheckTask));
            evidenceTask.configure(t -> t.dependsOn(usageTask, pureShip));

            // Downgrading the jar's own classes (docs/decisions/0007). Nothing in `check` or in a jar runs it: a consumer whose
            // port is written for a newer Java than its era runs puts this task's output where its loader's tooling takes the
            // jar (Loom's remapJar input, say), and no build that does not is changed.
            TaskProvider<Jar> jarTask = project.getTasks().named("jar", Jar.class);
            project.getTasks().register("myceliumDowngradeJar", DowngradeJarTask.class, t -> {
                t.setGroup("mycelium");
                t.setDescription("Downgrades the jar's own classes to the Java this target's era runs on, and proves it.");
                t.getInput().set(jarTask.flatMap(Jar::getArchiveFile));
                t.getClasspath().from(sourceSets.getByName("main").getCompileClasspath());
                t.getLibraryJars().from(privateLibraries);
                t.getLibraryRelocations().set(ext.getLibraryRelocations());
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
            project.afterEvaluate(p -> {
                List<String> artifactJars = ext.getArtifactJars().get();
                validateArtifactJars(artifactJars, p.getTasks().withType(Jar.class).getNames());
                if (ext.getPackMeta().isOn()) {
                    // Forge serves a mod's data only with a pack.mcmeta (Fabric and NeoForge make their own)
                    sourceSets.getByName("main").getResources().srcDir(packMetaTask.flatMap(PackMetaTask::getOutputDir));
                    p.getTasks().named("processResources", t -> t.dependsOn(packMetaTask));
                }
                if (ext.getMinecraft().isPresent() && !ext.getOverlays().getEntries().getOrElse(List.of()).isEmpty()) {
                    java.io.File overlay = OverlaySpec.choose(ext.getOverlays().getEntries().get(), ext.getMinecraft().get());
                    if (overlay != null) {
                        sourceSets.getByName("main").getResources().srcDir(overlay);
                    }
                }
                p.getTasks().withType(Jar.class).configureEach(jar -> {
                    if (!isArtifactJar(artifactJars, jar.getName())) {
                        return;
                    }
                    jar.from(evidenceTask.flatMap(EvidenceTask::getOutputDir), spec -> spec.into("META-INF/mycelium"));
                    jar.dependsOn(pureShip);
                    jar.from(pureShip.flatMap(PureShipTask::getClassesDir));
                    Provider<java.io.File> shipClassesDir = pureShip.flatMap(t -> t.getClassesDir().getAsFile());
                    // Exactly what ship put in makes way: a class from anywhere else whose entry ship also supplies, or whose
                    // outermost class it supplies, is dropped. Decided per entry from ship's own output, so it holds however
                    // the build is ordered.
                    // Bridge conformance cases are test code. Their data files stay out of the jar that ships (they reach the game-test
                    // run through the compiled output directories), and their classes too when the mod says so.
                    if (ext.getBridges().getConformance().getOrElse(false)) {
                        boolean shipData = ext.getBridges().getShipConformanceResources().getOrElse(false);
                        boolean shipClasses = !ext.getBridges().getConformanceClassesOutOfJar().getOrElse(false);
                        jar.getInputs().property("mycelium.conformance.shipResources", shipData);
                        jar.getInputs().property("mycelium.conformance.shipClasses", shipClasses);
                        if (!shipData) {
                            // leaving the data files out leaves their directories, empty: no jar entry should say there was something here
                            jar.setIncludeEmptyDirs(false);
                        }
                        Provider<java.io.File> conformanceData = bridgeImportTask.flatMap(t -> t.getConformanceResourcesDir().getAsFile());
                        String classesInfix = ext.getNamespace().get().replace('.', '/') + "/bridges/";
                        java.util.concurrent.atomic.AtomicReference<java.util.Set<String>> dataPaths = new java.util.concurrent.atomic.AtomicReference<>();
                        jar.exclude(element -> {
                            String path = element.getRelativePath().getPathString();
                            if (!shipData) {
                                if (dataPaths.get() == null) {
                                    try {
                                        dataPaths.set(Bridges.allFiles(conformanceData.get().toPath()).keySet());
                                    } catch (java.io.IOException e) {
                                        throw new org.gradle.api.GradleException("Mycelium bridges: the conformance data files could not be listed", e);
                                    }
                                }
                                if (dataPaths.get().contains(path)) {
                                    return true;
                                }
                            }
                            return !shipClasses && isConformanceClass(path, classesInfix);
                        });
                    }
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
        });
    }

    /** Whether a jar entry is a class of a bridge's conformance cases: {@code <namespace>/bridges/<slug>/conformance/...class}. */
    static boolean isConformanceClass(String path, String bridgesPrefix) {
        if (!path.startsWith(bridgesPrefix) || !path.endsWith(".class")) {
            return false;
        }
        String[] parts = path.substring(bridgesPrefix.length()).split("/");
        return parts.length >= 3 && parts[1].equals("conformance");
    }

    /** Whether a source directory is one Mycelium wrote (an import, a bridge, conformance cases): under the imported root. */
    static boolean isImported(java.io.File dir, java.io.File importedRoot) {
        return dir.getAbsoluteFile().toPath().startsWith(importedRoot.getAbsoluteFile().toPath());
    }

    /** Where pinned name data is kept: {@code -Pmycelium.names.cache}, the environment's {@code MYCELIUM_NAMES_CACHE}, or Gradle's user home. */
    static java.io.File namesCache(Project project) {
        Object property = project.findProperty("mycelium.names.cache");
        String environment = System.getenv("MYCELIUM_NAMES_CACHE");
        if (property != null && !String.valueOf(property).isBlank()) {
            return new java.io.File(String.valueOf(property));
        }
        if (environment != null && !environment.isBlank()) {
            return new java.io.File(environment);
        }
        return new java.io.File(project.getGradle().getGradleUserHomeDir(), "mycelium/names");
    }

    /** Whether a Jar task receives the artifact's code and evidence: by default only `jar`, the jar that ships; `"*"` is every Jar. */
    static boolean isArtifactJar(List<String> taskNames, String name) {
        return taskNames.isEmpty() ? name.equals("jar") : taskNames.contains("*") || taskNames.contains(name);
    }

    static void validateArtifactJars(List<String> taskNames, java.util.Set<String> available) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String name : taskNames) {
            if (name.equals("*") && seen.add(name)) {
                continue;
            }
            if (name.isBlank() || !seen.add(name) || !available.contains(name)) {
                throw new org.gradle.api.GradleException("Mycelium: artifactJars must name distinct Jar tasks in this project; '"
                        + name + "' is invalid (available: " + available + ")");
            }
        }
    }

    /** The bundle directory of every contract this project verifies and ran (not one Mycelium's own check proved). */
    private static ConfigurableFileCollection verifyBundles(ConfigurableFileCollection into, MyceliumExtension ext, Provider<Directory> reportDir,
                                                            Provider<List<String>> contracts, Provider<Boolean> isMod, Provider<String> verifyProperty) {
        // a provider, not a callable: the list is read when the build runs, after the contracts are chosen
        into.from(contracts.map(ids -> {
            List<Directory> dirs = new ArrayList<>();
            if (!ext.getVerify().get()) {
                return dirs;
            }
            if (ids.isEmpty() && isMod.get()) {
                return dirs;
            }
            java.nio.file.Path root = ext.getRoot().get().getAsFile().toPath();
            ContractRegistry registry = ContractRegistry.load(root.toFile());
            boolean proofs = isMod.get() && !"always".equals(verifyProperty.get());
            Proofs.Recorded recorded;
            try {
                recorded = proofs ? Proofs.load(root) : Proofs.Recorded.NONE;
                for (ContractRegistry.Entry e : registry.resolve(ids)) {
                    if (proofs && Proofs.provenUpstream(root, registry, e, recorded, neededEras(ext))) {
                        continue;
                    }
                    dirs.add(reportDir.get().dir(e.slug()));
                }
            } catch (java.io.IOException x) {
                throw new org.gradle.api.GradleException("Mycelium: cannot read the recorded proofs: " + x.getMessage(), x);
            }
            return dirs;
        }));
        return into;
    }

    /** The Javas this build's era gates run on, which a recorded proof must cover. */
    static List<Integer> neededEras(MyceliumExtension ext) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>(ext.resolvedEras());
        if (ext.getMinecraft().isPresent() && ext.getRoot().isPresent()) {
            out.add(ext.javaFor(ext.getMinecraft().get()));
        }
        return new ArrayList<>(out);
    }

    /** Source files the mod's own code is written in: what the derivation reads (a Kotlin or Scala mod references the same packages). */
    static final List<String> SOURCE_EXTENSIONS = List.of(".java", ".kt", ".scala");

    static boolean isSource(java.nio.file.Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString();
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /** What a mod's own sources reference: the contracts (by id) and the bridges (by slug) of the Mycelium checkout. */
    record Derived(List<String> contracts, List<String> bridges) {
    }

    /** The lines of a small generated list file, none for an empty one. */
    static List<String> lines(org.gradle.api.file.RegularFile file) {
        try {
            String text = Files.readString(file.getAsFile().toPath(), StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? List.<String>of() : List.of(text.split("\\R"));
        } catch (java.io.IOException x) {
            throw new org.gradle.api.GradleException("Mycelium: cannot read " + file.getAsFile() + ": " + x.getMessage(), x);
        }
    }

    /**
     * The contracts a mod's own sources reference under its namespace ({@code <namespace>.<package>}), by id; their
     * requirements come with them on import. Reads the main sources as the compiler does (never the generated imports, and the code,
     * not the comments: what a Stonecutter swap switched off is not a use) and the pure layer.
     */
    static List<String> deriveContracts(java.io.File myceliumRoot, java.util.Collection<java.io.File> mainRoots,
                                        java.util.Collection<java.io.File> pureRoots, String namespace, java.io.File generated) {
        return derive(myceliumRoot, mainRoots, pureRoots, namespace, generated).contracts();
    }

    /**
     * What the sources reference, as {@link #deriveContracts} reads them: the contracts, and the bridges ({@code <namespace>.bridges.<package>}).
     * A bridge's contract is among the contracts, since the bridge implements that contract's port. A bridge is taken when the mod
     * names it, never because it imported the contract's model (decision 0016, offer don't police).
     */
    static Derived derive(java.io.File myceliumRoot, java.util.Collection<java.io.File> mainRoots,
                          java.util.Collection<java.io.File> pureRoots, String namespace, java.io.File generated) {
        ContractRegistry registry = ContractRegistry.load(myceliumRoot);
        List<java.io.File> roots = new ArrayList<>();
        mainRoots.stream().filter(d -> !d.getAbsoluteFile().equals(generated.getAbsoluteFile())).forEach(roots::add);
        roots.addAll(pureRoots);
        StringBuilder all = new StringBuilder();
        for (java.io.File root : roots) {
            if (!root.exists()) {
                continue;
            }
            try (var walk = java.nio.file.Files.walk(root.toPath())) {
                for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) walk::iterator) {
                    if (java.nio.file.Files.isRegularFile(p) && isSource(p)
                            && !p.toAbsolutePath().startsWith(generated.toPath().toAbsolutePath())) {
                        all.append(withoutComments(java.nio.file.Files.readString(p, StandardCharsets.UTF_8))).append('\n');
                    }
                }
            } catch (java.io.IOException x) {
                throw new org.gradle.api.GradleException("Mycelium: cannot read " + root + " to find the contracts it uses: " + x.getMessage(), x);
            }
        }
        String text = all.toString();
        List<String> ids = new ArrayList<>();
        List<String> bridges = new ArrayList<>();
        // a bridge the mod's sources name brings its contract: the bridge implements that contract's port
        try {
            for (Bridges.Bridge b : Bridges.load(myceliumRoot.toPath())) {
                if (java.util.regex.Pattern.compile("(?<![\\w.])" + java.util.regex.Pattern.quote(namespace + "." + b.relativePackage()) + "(?![\\w])")
                        .matcher(text).find()) {
                    bridges.add(b.slug());
                    if (!ids.contains(b.contract())) {
                        ids.add(b.contract());
                    }
                }
            }
        } catch (java.io.IOException x) {
            throw new org.gradle.api.GradleException("Mycelium: cannot read the bridges: " + x.getMessage(), x);
        }
        for (ContractRegistry.Entry e : registry.all()) {
            if (e.status().equals("retired")) {
                continue;
            }
            String needle = namespace + "." + e.relativePackage();
            if (!ids.contains(e.id()) && java.util.regex.Pattern.compile("(?<![\\w.])" + java.util.regex.Pattern.quote(needle) + "(?![\\w])").matcher(text).find()) {
                ids.add(e.id());
            }
        }
        return new Derived(ids, bridges);
    }


    /**
     * The code of a Java source without its comments (what the compiler reads): a reference only a comment makes, such as the
     * branch a Stonecutter swap switched off, is not a use. String and character literals are kept whole.
     */
    static String withoutComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '"' || c == '\'') {
                boolean block = c == '"' && text.startsWith("\"\"\"", i);
                int start = i;
                i += block ? 3 : 1;
                while (i < n) {
                    char d = text.charAt(i);
                    if (d == '\\') {
                        i += 2;
                    } else if (block ? text.startsWith("\"\"\"", i) : d == c) {
                        i += block ? 3 : 1;
                        break;
                    } else if (!block && d == '\n') {
                        break;
                    } else {
                        i++;
                    }
                }
                out.append(text, start, Math.min(i, n));
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
    /**
     * The conventions of one project's extension: neutral defaults, then (when the root declares `myceliumDefaults`) the
     * root's values, then what only this project knows (its name, group, version).
     */
    static void conventions(Project project, MyceliumExtension ext, MyceliumExtension defaults) {
        neutral(ext);
        // The default is the group and the artifact: two mods in one group (a group is often a person's or a team's) must not share a
        // namespace, because what a mod imports is private to it only if the package is its own. A project with no group has nothing
        // of its own to build one from, and says so.
        java.util.concurrent.atomic.AtomicBoolean fellBack = new java.util.concurrent.atomic.AtomicBoolean();
        Provider<String> namespace = project.provider(() -> {
            String group = String.valueOf(project.getGroup());
            String id = packageSegment(ext.getArtifactId().get());
            if (group.isBlank() || group.equals("unspecified")) {
                fellBack.set(true);
                return "mycelium.imported." + id;
            }
            return group + "." + id + ".mycelium";
        });
        project.afterEvaluate(p -> {
            if (p.getPlugins().hasPlugin(JavaPlugin.class)) {
                String chosen = ext.getNamespace().getOrNull(); // evaluates the default only when nothing set a namespace
                if (fellBack.get()) {
                    p.getLogger().warn("Mycelium: {} has no group, so its namespace defaulted to {}. Set mycelium { namespace = \"...\" } (or a group)"
                            + " to name the package everything it imports lives under.", p.getPath(), chosen);
                }
            }
        });
        if (defaults != null) {
            neutral(defaults);
            ext.getRoot().convention(defaults.getRoot());
            ext.getContracts().convention(defaults.getContracts());
            ext.getVerify().convention(defaults.getVerify());
            ext.getFailOnUnused().convention(defaults.getFailOnUnused());
            ext.getArtifactJars().convention(defaults.getArtifactJars());
            ext.getEras().convention(defaults.getEras());
            ext.getDowngraderVersion().convention(defaults.getDowngraderVersion());
            ext.getMinecraft().convention(defaults.getMinecraft());
            ext.getLoader().convention(defaults.getLoader());
            ext.getLibraryRelocations().convention(defaults.getLibraryRelocations());
            ext.getIntegrations().convention(defaults.getIntegrations());
            ext.getPorts().convention(defaults.getPorts());
            ext.getSurfaceFailOnGrowth().convention(defaults.getSurfaceFailOnGrowth());
            ext.getSurfaceBaseline().convention(defaults.getSurfaceBaseline());
            ext.getPure().getRelease().convention(defaults.getPure().getRelease());
            ext.getPure().getSuiteMain().convention(defaults.getPure().getSuiteMain());
            ext.getPure().getStrict().convention(defaults.getPure().getStrict());
            ext.getPure().getForbidden().convention(defaults.getPure().getForbidden());
            ext.getPure().getSources().from(defaults.getPure().getSources());
            ext.getPure().getTests().from(defaults.getPure().getTests());
            ext.getPackMeta().getDescription().convention(defaults.getPackMeta().getDescription());
            ext.getPackMeta().getEnabled().convention(defaults.getPackMeta().getEnabled());
            ext.getOverlays().getEntries().convention(defaults.getOverlays().getEntries());
            ext.getBridges().getUse().convention(defaults.getBridges().getUse());
            ext.getBridges().getSkip().convention(defaults.getBridges().getSkip());
            ext.getBridges().getOverrides().convention(defaults.getBridges().getOverrides());
            ext.getBridges().getConformance().convention(defaults.getBridges().getConformance());
            ext.getBridges().getConformanceSourceSet().convention(defaults.getBridges().getConformanceSourceSet());
            ext.getBridges().getShipConformanceResources().convention(defaults.getBridges().getShipConformanceResources());
            ext.getBridges().getConformanceClassesOutOfJar().convention(defaults.getBridges().getConformanceClassesOutOfJar());
        }
        ext.getNamespace().convention(defaults == null ? namespace : defaults.getNamespace().orElse(namespace));
        ext.getTargetProfile().convention(defaults == null ? project.provider(project::getName)
                : defaults.getTargetProfile().orElse(project.provider(project::getName)));
        ext.getArtifactId().convention(defaults == null ? project.provider(project::getName)
                : defaults.getArtifactId().orElse(project.provider(project::getName)));
        ext.getArtifactVersion().convention(defaults == null ? project.provider(() -> String.valueOf(project.getVersion()))
                : defaults.getArtifactVersion().orElse(project.provider(() -> String.valueOf(project.getVersion()))));
        Provider<String> shade = ext.getNamespace().map(n -> n.replace('.', '_') + "_jvmdg");
        ext.getShadePrefix().convention(defaults == null ? shade : defaults.getShadePrefix().orElse(shade));
    }

    /** An artifact id as one segment of a Java package: lower case, letters, digits and underscores, not starting with a digit. */
    static String packageSegment(String id) {
        String s = id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (s.isEmpty()) {
            return "mod";
        }
        return Character.isDigit(s.charAt(0)) ? "_" + s : s;
    }

    /** The conventions that do not depend on a project. */
    static void neutral(MyceliumExtension ext) {
        ext.getVerify().convention(true);
        ext.getFailOnUnused().convention(false);
        ext.getArtifactJars().convention(List.of());
        ext.getLibraryRelocations().convention(List.of());
        ext.getDowngraderVersion().convention("2.0.1");
        ext.getPure().getRelease().convention(VerifyContractsTask.RELEASE);
        ext.getPure().getSuiteMain().convention("Run");
        ext.getPure().getStrict().convention(false);
        ext.getSurfaceFailOnGrowth().convention(false);
        ext.getBridges().getConformance().convention(false);
        ext.getCapsule().getOuterJava().convention(8);
        ext.getCapsule().getNestedJars().convention(List.of());
        ext.getCapsule().getMappedPrefixes().convention(List.of());
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
