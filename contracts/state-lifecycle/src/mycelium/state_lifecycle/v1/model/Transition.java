package mycelium.state_lifecycle.v1.model;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** A transition as data: its name, what it does to its host, and the host kinds it applies to. */
public record Transition(TransitionId id, Shape shape, Set<HostKind> kinds) {
    public Transition {
        if (id == null || shape == null || kinds == null) {
            throw new IllegalArgumentException("a transition has an id, a shape and host kinds");
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("transition " + id + " applies to no host kind");
        }
        kinds = Collections.unmodifiableSet(new TreeSet<>(kinds));
    }

    public boolean admits(HostKind kind) {
        return kinds.contains(kind);
    }
}
