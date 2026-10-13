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
 *         conformanceClassesOutOfJar()                         // ... and keep their classes out of the jar that ships
 *     }
 * }
 * </pre>
 *
 * <p>Conformance cases are test code, and a mod whose game tests live in {@code main} (Loom's and NeoForge's often do) would
 * ship them. The cases' <em>data files</em> (a datapack clock) are therefore kept out of the artifact jars by default, and still
 * reach every run that uses the compiled output directories (the game-test run); {@link #shipConformanceResources()} keeps them
 * in. The cases' <em>classes</em> stay in unless {@link #conformanceClassesOutOfJar()} says otherwise, since the mod's own tests,
 * compiled into the same jar, call them.
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

    /** Whether the conformance cases' data files stay in the jars that ship (default false: they are test data). */
    public abstract Property<Boolean> getShipConformanceResources();

    /** Whether the conformance cases' classes stay out of the jars that ship (default false: the mod's own tests may call them). */
    public abstract Property<Boolean> getConformanceClassesOutOfJar();

    /** Keep the conformance cases' data files in the jars that ship. */
    public void shipConformanceResources() {
        getShipConformanceResources().set(true);
    }

    /**
     * Keep the conformance cases' classes out of the jars that ship. Only for a mod whose tests that call them are not in
     * those jars, or never run from them: the jar's own test classes would reference classes it does not have.
     */
    public void conformanceClassesOutOfJar() {
        getConformanceClassesOutOfJar().set(true);
    }

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
