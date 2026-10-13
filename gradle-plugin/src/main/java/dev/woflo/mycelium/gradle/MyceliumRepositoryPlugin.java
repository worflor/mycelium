package dev.woflo.mycelium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.base.plugins.LifecycleBasePlugin;

/**
 * {@code dev.woflo.mycelium.repository}: the Mycelium repository's own gate. Applied only by the Mycelium root build,
 * never by a consumer. It applies {@code dev.woflo.mycelium} (the repository dogfoods its plugin) and gives the
 * repository a {@code check} that runs everything the repository promises: every harness, the pure layer's laws, every
 * era gate, and {@code myceliumRepository} (the repository against itself, fed by what the harnesses ran).
 * {@code myceliumRefreshVersions} rewrites the version list by hand, and {@code myceliumRefreshNames} pins name data.
 */
public final class MyceliumRepositoryPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(BasePlugin.class);
        project.getPluginManager().apply(MyceliumPlugin.class);
        TaskProvider<VerifyContractsTask> verify = project.getTasks().named("myceliumVerify", VerifyContractsTask.class);
        TaskProvider<RepositoryCheckTask> repository = project.getTasks().register("myceliumRepository", RepositoryCheckTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Checks the Mycelium repository against what it states: registry, packages, copied patterns, the era table, documents.");
            t.getRoot().set(project.getLayout().getProjectDirectory());
            t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/repository"));
            t.getHarnessResults().set(verify.flatMap(v -> v.getReportDir().file("results.json")));
            t.dependsOn(verify);
        });
        // After every harness and every era gate held, record what was proved (contracts/proofs.json): a mod's myceliumVerify
        // takes a contract whose sources are unchanged as proven, and runs a harness only when they differ (docs/decisions/0017).
        MyceliumExtension ext = project.getExtensions().getByType(MyceliumExtension.class);
        TaskProvider<ProofRecordTask> proofs = project.getTasks().register("myceliumRecordProofs", ProofRecordTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Records contracts/proofs.json once every harness and every era gate has passed (a mod then need not re-run them).");
            t.getRoot().set(project.getLayout().getProjectDirectory());
            t.getEras().set(project.provider(ext::resolvedEras));
            // it records what these reports say held, not what the registry lists: a narrowed or skipped gate records nothing
            t.getVerifyResults().set(verify.flatMap(v -> v.getReportDir().file("results.json")));
            t.getEraReports().from(project.provider(() -> ext.resolvedEras().stream()
                    .map(java -> project.getLayout().getBuildDirectory().file("mycelium/eras/java" + java + "/era.json").get().getAsFile()).toList()));
            t.dependsOn(verify, "myceliumEras");
        });
        project.getTasks().register("myceliumRefreshVersions", RefreshVersionsTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Rewrites eras/versions.json from Mojang's launcher manifest (by hand, when a version ships).");
            t.getRoot().set(project.getLayout().getProjectDirectory());
        });
        project.getTasks().register("myceliumRefreshNames", RefreshNamesTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Pins name data in eras/names.json by SHA-256 for given versions, or reports pins whose files changed (by hand).");
            t.getRoot().set(project.getLayout().getProjectDirectory());
        });
        JavaToolchainService toolchains = project.getExtensions().getByType(JavaToolchainService.class);
        // Every bridge variant linked against the versions it covers (decision 0016): Mycelium's own proof, before any mod builds one.
        TaskProvider<BridgeLinkageTask> bridgeLinkage = project.getTasks().register("myceliumBridgeLinkage", BridgeLinkageTask.class, t -> {
            t.setGroup("mycelium");
            t.setDescription("Compiles each bridge variant against the game and holds its references to every version it covers.");
            t.getRoot().set(project.getLayout().getProjectDirectory());
            t.getCache().set(project.getLayout().dir(project.provider(() -> MyceliumPlugin.namesCache(project))));
            t.getOffline().set(project.getGradle().getStartParameter().isOffline());
            // a compiler as new as the newest Java a game runs on: the one running Gradle may be older than the class files it reads
            t.getJavac().set(toolchains.compilerFor(spec -> spec.getLanguageVersion().set(
                    JavaLanguageVersion.of(ext.resolvedEras().stream().mapToInt(Integer::intValue).max().orElse(21)))).map(c -> c.getExecutablePath()));
            t.getReportDir().set(project.getLayout().getBuildDirectory().dir("mycelium/bridge-linkage"));
        });
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, check -> check.dependsOn(
                repository, "myceliumVerify", "myceliumPurity", "myceliumPureCompile", "myceliumEras", proofs, bridgeLinkage));
    }
}
