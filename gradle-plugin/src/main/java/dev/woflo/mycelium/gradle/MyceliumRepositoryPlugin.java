package dev.woflo.mycelium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.tasks.TaskProvider;
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
        project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, check -> check.dependsOn(
                repository, "myceliumVerify", "myceliumPurity", "myceliumPureCompile", "myceliumEras"));
    }
}
