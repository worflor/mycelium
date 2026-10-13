package dev.woflo.mycelium.gradle;

import org.gradle.api.provider.ListProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code mycelium { checks { ... } }} (docs/decisions/0017): a level per check and allowances for single sites.
 *
 * <pre>
 * mycelium {
 *     checks {
 *         warn("I1")                       // off | warn | fail, per check id
 *         off("S1")
 *         level("P3", "fail")              // the same, by name
 *         allow("P3", "kernel/Rng.java", "seeded by the caller")
 *     }
 * }
 * </pre>
 *
 * Stored as encoded lines in order, so a later setting wins; {@link #lines()} is what the tasks receive.
 */
public abstract class ChecksSpec {
    /** The encoded settings, in the order written. */
    public abstract ListProperty<String> getSettings();

    public void level(String id, String level) {
        Checks.validateId(id);
        getSettings().add(Checks.encodeLevel(id, Checks.Level.parse(level)));
    }

    public void off(String... ids) {
        for (String id : ids) {
            level(id, "off");
        }
    }

    public void warn(String... ids) {
        for (String id : ids) {
            level(id, "warn");
        }
    }

    public void fail(String... ids) {
        for (String id : ids) {
            level(id, "fail");
        }
    }

    /**
     * Allows one site for one check. {@code site} is a source path (matched as a path suffix: {@code kernel/Rng.java}), a
     * Minecraft version for a per-version check, or {@code "*"}. A reason is required: it is recorded in the evidence manifest.
     */
    public void allow(String id, String site, String reason) {
        Checks.validateId(id);
        if (site == null || site.isBlank() || site.contains("|")) {
            throw new IllegalArgumentException("Mycelium: allow(" + id + ", ...) needs a site (a source path, a version or \"*\")");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Mycelium: allow(" + id + ", \"" + site + "\", ...) needs a reason: it is recorded in the evidence manifest");
        }
        getSettings().add(Checks.encodeAllowance(id, site, reason.strip()));
    }

    /** This spec's settings after {@code inherited} (a defaults block's), as the tasks receive them. */
    public List<String> merged(ChecksSpec inherited) {
        List<String> out = new ArrayList<>();
        if (inherited != null) {
            out.addAll(inherited.getSettings().getOrElse(List.of()));
        }
        out.addAll(getSettings().getOrElse(List.of()));
        return out;
    }
}
