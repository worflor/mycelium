package dev.woflo.mycelium.gradle;

import org.gradle.api.Action;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code mycelium { range { ... } }}: the Minecraft versions this artifact says it runs on, and what proves it
 * (docs/decisions/0009). Either read from the consumer's ledger,
 *
 * <pre>
 * range { ledger(rootProject.file("artifacts.json"), "fabric-${sc.current.version}") }
 * </pre>
 * or written here:
 * <pre>
 * range { loader = "fabric"; from = "1.21"; to = "1.21.1"; keystones("1.21.1"); flex("1.21") }
 * </pre>
 * {@code myceliumRange} holds the claim to the era table, the proofs and the loader's own metadata, and its report goes
 * into the evidence manifest.
 */
public abstract class RangeSpec {
    private File ledgerFile;
    private String ledgerId;
    private ArtifactLedger.Artifact fromLedger;
    private boolean linkageDeclared;

    /** Whether {@code myceliumLinkage} is on: the artifact is held to the game's published names across the range (docs/decisions/0010). */
    @Nested
    public abstract LinkageSpec getLinkage();

    public void linkage(Action<? super LinkageSpec> action) {
        linkageDeclared = true;
        action.execute(getLinkage());
    }

    public boolean isLinkageDeclared() {
        return linkageDeclared;
    }

    /** The loader as {@code eras/loaders.json} names it: its walls and its metadata are the artifact's. */
    public abstract Property<String> getLoader();

    /** Every platform of the loader's family the artifact claims (a plugin: Spigot, Paper, Purpur, Folia). Empty: the loader. */
    public abstract ListProperty<String> getPlatforms();

    /** The first and last Minecraft version claimed, inclusive; the last may be a line ({@code 26.3.x}). */
    public abstract Property<String> getFrom();

    public abstract Property<String> getTo();

    /** Versions servers really run, and the one each side of a wall; proofs not yet run are {@link #getPending}. */
    public abstract ListProperty<String> getKeystones();

    public abstract ListProperty<String> getFlex();

    public abstract ListProperty<String> getPending();

    public void keystones(String... versions) {
        getKeystones().addAll(versions);
    }

    public void flex(String... versions) {
        getFlex().addAll(versions);
    }

    public void pending(String... versions) {
        getPending().addAll(versions);
    }

    /** Reads the claim from {@code ledger}'s artifact {@code id}; a miss names the closest rows. */
    public void ledger(File ledger, String id) {
        this.ledgerFile = ledger;
        this.ledgerId = id;
        this.fromLedger = null;
    }

    /** Whether a range is declared at all, by a ledger or by hand. */
    public boolean isDeclared() {
        return ledgerFile != null || getLoader().isPresent() || getFrom().isPresent() || getTo().isPresent();
    }

    /** The claim, as declared. */
    public ArtifactLedger.Artifact artifact() {
        if (ledgerFile != null) {
            if (fromLedger == null) {
                fromLedger = ArtifactLedger.load(ledgerFile).require(ledgerId);
            }
            return fromLedger;
        }
        for (String key : new String[]{"loader", "from", "to"}) {
            boolean set = switch (key) {
                case "loader" -> getLoader().isPresent();
                case "from" -> getFrom().isPresent();
                default -> getTo().isPresent();
            };
            if (!set) {
                throw new IllegalStateException("mycelium { range { ... } } declares no '" + key + "': a range names its loader and the"
                        + " versions it runs from and to, or reads them from a ledger (ledger(file, id))");
            }
        }
        List<ArtifactLedger.Proof> keystones = new ArrayList<>();
        getKeystones().get().forEach(v -> keystones.add(new ArtifactLedger.Proof(v, List.of())));
        List<ArtifactLedger.Proof> flex = new ArrayList<>();
        getFlex().get().forEach(v -> flex.add(new ArtifactLedger.Proof(v, List.of())));
        return new ArtifactLedger.Artifact(getLoader().get() + "-" + getFrom().get(), getLoader().get(), getFrom().get(),
                getPlatforms().get(), getFrom().get(), getTo().get(), keystones, flex, getPending().get(), Map.of());
    }
}
