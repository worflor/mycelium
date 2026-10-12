package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.HostRef;

import java.util.Optional;

/** Resolves a ref by its host id and ignores the incarnation: a closure aimed at a logged-out player reaches the new one. */
public final class IdOnlyResolver<H> extends MemoryHostStore<H> {
    @Override
    public Optional<H> resolve(HostRef ref) {
        HostRef current = live.get(ref.host());
        return current == null ? Optional.<H>empty() : Optional.of(handles.get(current));
    }
}
