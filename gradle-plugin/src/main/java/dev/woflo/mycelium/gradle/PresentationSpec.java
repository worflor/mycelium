package dev.woflo.mycelium.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;

/**
 * {@code mycelium { presentation { ... } }}: where a consumer keeps the programs of {@code item.presentation@1}
 * (docs/decisions/0012). The defaults are the layout the contract's SPEC describes, so a consumer with
 * {@code src/main/presentation/} declares nothing.
 *
 * <pre>
 * mycelium {
 *     minecraft = "1.21.4"
 *     presentation {
 *         sources = layout.projectDirectory.dir("src/main/presentation")   // the default
 *     }
 * }
 * </pre>
 */
public abstract class PresentationSpec {
    /** The programs: every {@code *.json} here but the catalog and the lock. Default {@code src/main/presentation}. */
    public abstract DirectoryProperty getSources();

    /** The component catalog the programs are checked against. Default {@code components.json} in {@link #getSources()}. */
    public abstract RegularFileProperty getComponents();

    /** The committed lock of legacy codes. Default {@code presentation.lock.json} in {@link #getSources()}. */
    public abstract RegularFileProperty getLock();
}
