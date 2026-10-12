package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Compilation;
import mycelium.state_lifecycle.v1.model.Policy;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.StandardCompiler;
import mycelium.state_lifecycle.v1.model.Transition;
import mycelium.state_lifecycle.v1.model.Transitions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Accepts a slot with a missing policy by treating the gap as KEEP: the silent default the contract forbids. */
public final class DefaultPolicy implements RegistryCompiler {
    @Override
    public Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        List<Slot<?>> filled = new ArrayList<>();
        for (Slot<?> slot : slots) {
            filled.add(fill(transitions, slot));
        }
        return StandardCompiler.INSTANCE.compile(transitions, filled);
    }

    private static <T> Slot<T> fill(Transitions transitions, Slot<T> slot) {
        Slot.Builder<T> b = slot.toBuilder();
        for (Transition t : transitions.all()) {
            if (t.admits(slot.scope()) && !slot.policy(t.id()).isPresent()) {
                b.on(t.id(), Policy.<T>keep());
            }
        }
        return b.build();
    }
}
