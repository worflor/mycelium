package mycelium.block_catalog.v2.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A finite immutable snapshot of known block identifiers, property domains, and default states.
 *
 * <p>Each domain keeps the order the capture offered it, because a native registry's order carries
 * meaning an alphabetical order destroys: a direction enum's declaration order, and numeric values
 * whose text order is not their numeric order. Block identifiers and property names are sorted, so
 * the snapshot's map iteration stays deterministic across captures.
 */
public class BlockCatalog {
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern PROPERTY = Pattern.compile("[a-z0-9_]+");

    private final String version;
    private final Map<String, Entry> blocks;

    public BlockCatalog(String version, Map<String, Entry> blocks) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must be nonblank");
        }
        Objects.requireNonNull(blocks, "blocks");
        TreeMap<String, Entry> copy = new TreeMap<>();
        for (Map.Entry<String, Entry> block : blocks.entrySet()) {
            String id = block.getKey();
            if (id == null || !BLOCK_ID.matcher(id).matches()) {
                throw new IllegalArgumentException("invalid canonical block id: " + id);
            }
            if (block.getValue() == null) {
                throw new IllegalArgumentException("missing block entry: " + id);
            }
            if (copy.putIfAbsent(id, validateEntry(id, block.getValue())) != null) {
                throw new IllegalArgumentException("duplicate block id: " + id);
            }
        }
        this.version = version;
        this.blocks = Collections.unmodifiableMap(copy);
    }

    public String version() {
        return version;
    }

    public Map<String, Entry> blocks() {
        return blocks;
    }

    /** Return a fully defaulted state with explicit properties applied, or reject an unknown or invalid value. */
    public Map<String, String> normalize(String id, Map<String, String> explicitProperties) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(explicitProperties, "explicitProperties");
        Entry entry = blocks.get(id);
        if (entry == null) {
            throw new IllegalArgumentException("unknown block id: " + id);
        }
        TreeMap<String, String> state = new TreeMap<>(entry.defaults());
        for (Map.Entry<String, String> explicit : explicitProperties.entrySet()) {
            String property = explicit.getKey();
            String value = explicit.getValue();
            List<String> domain = entry.properties().get(property);
            if (domain == null) {
                throw new IllegalArgumentException("unknown property " + property + " for " + id);
            }
            if (!domain.contains(value)) {
                throw new IllegalArgumentException("invalid value " + value + " for " + id + "[" + property + "]");
            }
            state.put(property, value);
        }
        return Collections.unmodifiableMap(state);
    }

    private static Entry validateEntry(String id, Entry raw) {
        Objects.requireNonNull(raw.properties(), "properties for " + id);
        Objects.requireNonNull(raw.defaults(), "defaults for " + id);
        TreeMap<String, List<String>> properties = new TreeMap<>();
        for (Map.Entry<String, List<String>> property : raw.properties().entrySet()) {
            String name = property.getKey();
            if (name == null || !PROPERTY.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid property name for " + id + ": " + name);
            }
            List<String> values = property.getValue();
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("property domain must be nonempty: " + id + "[" + name + "]");
            }
            // A set that keeps insertion order rejects duplicates without reordering the capture.
            LinkedHashSet<String> domain = new LinkedHashSet<>();
            for (String value : values) {
                if (!validValue(value)) {
                    throw new IllegalArgumentException("invalid property value for " + id + "[" + name + "]: " + value);
                }
                if (!domain.add(value)) {
                    throw new IllegalArgumentException("duplicate property value for " + id + "[" + name + "]: " + value);
                }
            }
            properties.put(name, List.copyOf(domain));
        }
        if (!raw.defaults().keySet().equals(properties.keySet())) {
            throw new IllegalArgumentException("defaults must name exactly the declared properties for " + id);
        }
        TreeMap<String, String> defaults = new TreeMap<>();
        for (String name : properties.keySet()) {
            String value = raw.defaults().get(name);
            if (!properties.get(name).contains(value)) {
                throw new IllegalArgumentException("default is outside the property domain: " + id + "[" + name + "]=" + value);
            }
            defaults.put(name, value);
        }
        return new Entry(Collections.unmodifiableMap(properties), Collections.unmodifiableMap(defaults));
    }

    private static boolean validValue(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) || "[],=".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * The finite domain of every state property and the exact fully defaulted state for one block.
     *
     * <p>Each domain list is the capture's own order; the catalogue sorts the property names it exposes.
     */
    public record Entry(Map<String, List<String>> properties, Map<String, String> defaults) {
    }
}
