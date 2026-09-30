package dev.woflo.mycelium.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * {@code mycelium { pure { ... } }}: the artifact's pure layer (docs/eras.md), the code that is the same on every version
 * and loader, and so reaches every era for free.
 *
 * <pre>
 * mycelium {
 *     pure {
 *         sources.from(rootProject.file("kernels/src"))
 *         tests.from(rootProject.file("kernels/test"))
 *         release = 21
 *         strict = true
 *     }
 * }
 * </pre>
 */
public abstract class PureSpec {
    /** The pure layer's sources (source roots or single files). Imported contract models join it automatically. */
    public abstract ConfigurableFileCollection getSources();

    /** Its suites: every class named {@link #getSuiteMain()} with a {@code main} is one (the Mycelium harness is on the path). */
    public abstract ConfigurableFileCollection getTests();

    /** The Java release the pure layer is written in; eras older than this get downgraded bytecode. Default 17. */
    public abstract Property<Integer> getRelease();

    /** The simple name of a suite's main class. Default {@code Run}. */
    public abstract Property<String> getSuiteMain();

    /**
     * Whether the pure layer is held to every check the compiler has: {@code -Xlint:all}, and a warning fails the
     * build ({@code -Werror}). The layer compiles on its own, so nothing outside it (a loader's deprecated API, a
     * platform's raw types) can be what warns: every warning is the artifact's own. Default false.
     */
    public abstract Property<Boolean> getStrict();

    /**
     * Packages the pure layer must never mention, beyond the defaults (Minecraft, every loader, Mixin, each declared
     * integration's API, and the artifact's own platform packages).
     */
    public abstract ListProperty<String> getForbidden();

    public void forbid(String... packages) {
        getForbidden().addAll(packages);
    }
}
