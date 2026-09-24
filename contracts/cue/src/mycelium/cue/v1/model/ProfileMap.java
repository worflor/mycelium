package mycelium.cue.v1.model;

import java.util.Optional;

/** Total-or-explicit profile resolution: a profile either resolves a key directly, resolves it through a
 * declared fallback, or the lookup is explicitly absent — never a silent skip. */
public interface ProfileMap {
    /** The key convention a profile's fallback mapping is resolved under. */
    String FALLBACK_KEY = "*";

    Optional<String> resolve(ProfileId profile, String key);

    boolean hasFallback(ProfileId profile);
}
