package mycelium.state_lifecycle.v1.model;

import java.util.Objects;

/**
 * The stable identity of a host: its kind, the save it belongs to, and the token that names it there (a UUID, a
 * dimension key). Identity says nothing about which runtime object currently stands for the host; that is the
 * {@link Incarnation}.
 */
public record HostId(HostKind kind, String world, String id) implements Comparable<HostId> {
    public HostId {
        Objects.requireNonNull(kind, "kind");
        if (world == null || world.trim().isEmpty()) {
            throw new IllegalArgumentException("a host id names its world");
        }
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("a host id has a token");
        }
    }

    @Override
    public int compareTo(HostId other) {
        int c = kind.compareTo(other.kind);
        if (c == 0) {
            c = world.compareTo(other.world);
        }
        return c != 0 ? c : id.compareTo(other.id);
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + world + "/" + id;
    }
}
