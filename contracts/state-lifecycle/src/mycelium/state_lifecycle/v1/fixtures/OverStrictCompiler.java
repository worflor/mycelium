package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Compilation;
import mycelium.state_lifecycle.v1.model.Diagnostic;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.StandardCompiler;
import mycelium.state_lifecycle.v1.model.Transition;
import mycelium.state_lifecycle.v1.model.Transitions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Demands a policy for every declared transition, whatever the slot's scope: a player slot is refused for having no
 * policy at {@code mycelium:load}, which applies to entities, levels and the server. The scope filter is forgotten, so no
 * sound declaration is ever accepted.
 */
public final class OverStrictCompiler implements RegistryCompiler {
    @Override
    public Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        Compilation real = StandardCompiler.INSTANCE.compile(transitions, slots);
        if (real instanceof Compilation.Rejected) {
            return real;
        }
        List<Diagnostic> found = new ArrayList<>();
        for (Slot<?> slot : slots) {
            for (Transition t : transitions.all()) {
                if (!slot.policy(t.id()).isPresent()) {
                    found.add(new Diagnostic(Diagnostic.Code.MISSING_POLICY, slot.id(), t.id(),
                            "slot '" + slot.id() + "' declares no policy for transition '" + t.id() + "'"));
                }
            }
        }
        return found.isEmpty() ? real : new Compilation.Rejected(found);
    }
}
