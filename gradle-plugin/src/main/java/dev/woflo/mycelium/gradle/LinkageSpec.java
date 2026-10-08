package dev.woflo.mycelium.gradle;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * {@code mycelium { range { linkage { ... } } }}: hold the finished artifact to the game's published names on every version
 * of its claimed range that has name data (docs/decisions/0010). Declaring the block turns {@code myceliumLinkage} on; a
 * range with no block is held to the era table, the proofs and the loader's metadata only.
 *
 * <pre>
 * range {
 *     ledger(rootProject.file("../artifacts.json"), "forge-${sc.current.version}")
 *     linkage { archive.set(tasks.named&lt;RemapJarTask&gt;("remapJar").flatMap { it.archiveFile }) }
 * }
 * </pre>
 */
public abstract class LinkageSpec {
    /**
     * The artifact as it ships, in the names its loader runs on: what remaps a development jar to those names (Forge's
     * reobfuscation, Fabric's remap) must have run. Defaults to the {@code jar} task's archive, or the capsule's when one is
     * declared. A reference is read from its classes, never from the evidence it carries.
     */
    public abstract RegularFileProperty getArchive();

    /** Entries of the archive that are jars carrying the artifact's classes (a capsule's payload). Defaults to the capsule's. */
    public abstract ListProperty<String> getNestedJars();

    public void nestedJar(String entry) {
        getNestedJars().add(entry);
    }

    /**
     * The naming of the game the artifact's classes reference ({@link Eras#NAMES}); defaults to what the era table says the
     * artifact's loader runs under on each version. A plugin states {@code api}: it names no game class, only the API the
     * server publishes, and the loader's row is the one that platform runs under.
     */
    public abstract Property<String> getNamespace();

    /**
     * Directories of compiled classes whose classes the archive carries but the gate does not hold: the ones the artifact
     * chooses between at runtime and loads only on a server they are for (a flavor of a plugin's jar, which refuses a server
     * whose API it was not compiled against). Every class the archive carries that is in none of them is held on every
     * version of the range; the report counts what was left out.
     */
    public abstract ConfigurableFileCollection getExclude();

    public void exclude(Object... classDirectories) {
        getExclude().from(classDirectories);
    }

    /**
     * An exact dedicated-server class inventory for the target {@code minecraft}, remapped into the artifact's runtime namespace.
     * When present, the linkage gate intersects it with published name data for this target version, so merged client names
     * cannot make a server-only call pass. The producer must supply the final game-only server surface, not a merged dev jar.
     */
    public abstract RegularFileProperty getServerRuntimeArchive();

    /** The namespace of {@link #getServerRuntimeArchive()}; it must match the artifact's references for the target version. */
    public abstract Property<String> getServerRuntimeNamespace();

    /** Internal-name prefixes inventoried as game classes in the server archive (for example {@code net/minecraft/}). */
    public abstract ListProperty<String> getServerRuntimeOwns();

    /** The name data's lock; defaults to the Mycelium checkout's {@code eras/names.json}. */
    public abstract RegularFileProperty getNames();

    /**
     * Where pinned name data is kept once fetched (by SHA-256). Defaults to {@code mycelium/names} under Gradle's user home,
     * or {@code -Pmycelium.names.cache}, or the environment's {@code MYCELIUM_NAMES_CACHE}.
     */
    public abstract DirectoryProperty getCache();
}
