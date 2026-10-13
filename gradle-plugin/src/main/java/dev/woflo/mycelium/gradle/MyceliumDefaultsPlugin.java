package dev.woflo.mycelium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * {@code dev.woflo.mycelium.defaults}: apply to the root project of a multi-project build (Stonecutter: one project per
 * version and loader) and write the mod's {@code mycelium { }} once, as {@code myceliumDefaults { }}. Every project that
 * applies {@code dev.woflo.mycelium} inherits it; a project's own setting wins, and what only a project knows (its
 * Minecraft version, its name) stays in the project (docs/decisions/0017).
 *
 * <pre>
 * // root build script
 * plugins { id("dev.woflo.mycelium.defaults") }
 * myceliumDefaults {
 *     root = rootProject.file("../mycelium")
 *     namespace = "com.example.mymod.mycelium"
 *     integration("voicechat", "com.example.voice", "de.maxhenkel.voicechat")
 *     pure { sources.from(rootProject.file("kernels/src")); release = 21 }
 * }
 * // each project: mycelium { minecraft = sc.current.version }
 * </pre>
 *
 * A project's own scalar setting wins over the default. A list a project writes (its own {@code contracts(...)},
 * {@code integration(...)}, {@code ports(...)}) replaces the default list, except the pure layer's {@code sources} and {@code tests},
 * which add up; the check levels and allowances merge (the project's after the default's).
 */
public final class MyceliumDefaultsPlugin implements Plugin<Project> {
    /** The type of the root's block, distinct from the {@code mycelium} block a project's own plugin creates. */
    public abstract static class Defaults extends MyceliumExtension {
    }

    @Override
    public void apply(Project project) {
        Defaults defaults = project.getExtensions().create("myceliumDefaults", Defaults.class);
        defaults.project = project;
        MyceliumPlugin.neutral(defaults);
    }
}
