package mycelium.item_component.v1.model;

import java.util.Objects;

/** An immutable, validated identifier token for an item component (e.g. {@code "example:card_instance"}). */
public record ComponentId(String namespace, String path) implements Comparable<ComponentId> {
    public ComponentId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.trim().isEmpty() || !isValidNamespace(namespace)) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (path.trim().isEmpty() || !isValidPath(path)) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static ComponentId of(String raw) {
        Objects.requireNonNull(raw, "raw");
        int colon = raw.indexOf(':');
        if (colon < 0) {
            return new ComponentId("minecraft", raw);
        }
        return new ComponentId(raw.substring(0, colon), raw.substring(colon + 1));
    }

    public static ComponentId of(String namespace, String path) {
        return new ComponentId(namespace, path);
    }

    private static boolean isValidNamespace(String ns) {
        for (int i = 0; i < ns.length(); i++) {
            char c = ns.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isValidPath(String p) {
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/' || c == '.')) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }

    @Override
    public int compareTo(ComponentId o) {
        int cmp = namespace.compareTo(o.namespace);
        return cmp != 0 ? cmp : path.compareTo(o.path);
    }
}
