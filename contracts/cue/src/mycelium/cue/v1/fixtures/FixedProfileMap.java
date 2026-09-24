package mycelium.cue.v1.fixtures;

import mycelium.cue.v1.model.ProfileId;
import mycelium.cue.v1.model.ProfileMap;

import java.util.Map;
import java.util.Optional;

/** Reference profile map: a fixed table of profile -> (key -> resolved key), where the fallback (if any)
 * is stored under {@link ProfileMap#FALLBACK_KEY}. */
public final class FixedProfileMap implements ProfileMap {
    private final Map<ProfileId, Map<String, String>> mappings;

    public FixedProfileMap(Map<ProfileId, Map<String, String>> mappings) {
        this.mappings = Map.copyOf(mappings);
    }

    @Override
    public Optional<String> resolve(ProfileId profile, String key) {
        return Optional.ofNullable(mappings.getOrDefault(profile, Map.of()).get(key));
    }

    @Override
    public boolean hasFallback(ProfileId profile) {
        return mappings.getOrDefault(profile, Map.of()).containsKey(FALLBACK_KEY);
    }
}
