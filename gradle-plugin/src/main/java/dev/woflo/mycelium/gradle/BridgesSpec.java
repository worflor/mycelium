package dev.woflo.mycelium.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * {@code mycelium { bridges { ... } }} (docs/decisions/0016): what a mod does with the bridges its contracts bring. A bridge
 * is an offer. By default the mod gets every bridge of every contract it imports, the variant its target version draws; here
 * it takes fewer, replaces one with a class of its own, or turns on the conformance cases.
 *
 * <pre>
 * mycelium {
 *     bridges {
 *         override("item-carrier", "com.example.MyCarrier")   // your class instead; it must implement the bridge's port
 *         conformance()                                        // add the bridges' game-test cases to your game-test sources
 *     }
 * }
 * </pre>
 */
public abstract class BridgesSpec {
    /** Bridges to take, by slug. Empty means every bridge the imported contracts bring; a list replaces that. */
    public abstract ListProperty<String> getUse();

    /** Bridges not to take, by slug: the mod writes that half itself, natively. */
    public abstract ListProperty<String> getSkip();

    /** Bridges whose variant is replaced by the mod's own class (slug to fully qualified class name). */
    public abstract MapProperty<String, String> getOverrides();

    /** Whether the conformance cases of the bridges in use are added to the mod's game-test sources. */
    public abstract Property<Boolean> getConformance();

    /**
     * The source set the conformance cases join. Empty means the {@code gametest} source set when the project has one, else
     * {@code main}, where a mod that keeps its game tests beside its code (Loom's and NeoForge's server game tests often are)
     * has them.
     */
    public abstract Property<String> getConformanceSourceSet();

    /** Take only these bridges. */
    public void use(String... slugs) {
        getUse().addAll(slugs);
    }

    /** Do not take these bridges. */
    public void skip(String... slugs) {
        getSkip().addAll(slugs);
    }

    /**
     * Replace the bridge's variant with {@code className}, a class in the mod's own sources. It must implement the bridge's
     * port; the contract's laws apply to it and nothing else does (the check B1 holds the class to the port).
     */
    public void override(String slug, String className) {
        if (slug == null || slug.isBlank() || className == null || className.isBlank() || className.contains("/")) {
            throw new IllegalArgumentException("Mycelium: override(slug, className) takes a bridge slug and a fully qualified class name");
        }
        getOverrides().put(slug, className);
    }

    /** Add the conformance cases to the mod's game-test sources. */
    public void conformance() {
        getConformance().set(true);
    }

    /** Add the conformance cases to the named source set. */
    public void conformance(String sourceSet) {
        getConformance().set(true);
        getConformanceSourceSet().set(sourceSet);
    }
}
