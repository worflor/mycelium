package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Compilation;
import mycelium.state_lifecycle.v1.model.Diagnostic;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.StandardCompiler;
import mycelium.state_lifecycle.v1.model.Transitions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Rejects the right declarations with the right codes, but its messages say only what kind of problem it is: no slot, no transition. */
public final class TerseDiagnostics implements RegistryCompiler {
    @Override
    public Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        Compilation real = StandardCompiler.INSTANCE.compile(transitions, slots);
        if (!(real instanceof Compilation.Rejected rejected)) {
            return real;
        }
        List<Diagnostic> terse = new ArrayList<>();
        for (Diagnostic d : rejected.diagnostics()) {
            terse.add(new Diagnostic(d.code(), d.slot(), d.transition(), "invalid slot declaration (" + d.code() + ")"));
        }
        return new Compilation.Rejected(terse);
    }
}
