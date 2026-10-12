package mycelium.state_lifecycle.v1.model;

import java.util.Objects;

/**
 * What scheduled or deferred work captures instead of a runtime object: a host and the incarnation it meant. It is
 * data. Resolving it ({@link HostStore#resolve}) gives the object only while that incarnation is the host's current one;
 * after a logout, respawn or reload it gives nothing, and never the host's next object. A ref is valid within one run of
 * the server: it is not persisted.
 */
public record HostRef(HostId host, Incarnation incarnation) implements Comparable<HostRef> {
    public HostRef {
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(incarnation, "incarnation");
    }

    @Override
    public int compareTo(HostRef other) {
        int c = host.compareTo(other.host);
        return c != 0 ? c : incarnation.compareTo(other.incarnation);
    }

    @Override
    public String toString() {
        return host + incarnation.toString();
    }
}
