package mycelium.state_lifecycle.v1.model;

import java.util.Collection;

/** The declaration check, as a seam so the harness can run it against broken ones. {@link StandardCompiler} is the contract's. */
public interface RegistryCompiler {
    Compilation compile(Transitions transitions, Collection<Slot<?>> slots);
}
