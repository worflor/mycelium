package mycelium.state_lifecycle.v1.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The slots of a mod, checked: every slot has a policy for every transition its scope admits, every policy means
 * something on its transition, durable slots have codecs, dependencies are acyclic. Only a compiler makes one, so holding
 * one is the proof.
 *
 * <p>Order is fixed here, once: within a scope, dependencies come first, and slots with nothing between them come in
 * id order (law L7). Declaration order never matters.
 */
public final class SlotRegistry {
    private final Transitions transitions;
    private final Map<SlotId, Slot<?>> byId;
    private final Map<HostKind, List<Slot<?>>> planOrder;

    SlotRegistry(Transitions transitions, Map<SlotId, Slot<?>> byId, Map<HostKind, List<Slot<?>>> planOrder) {
        this.transitions = transitions;
        this.byId = Collections.unmodifiableMap(new TreeMap<>(byId));
        Map<HostKind, List<Slot<?>>> copy = new EnumMap<>(HostKind.class);
        for (HostKind kind : HostKind.values()) {
            List<Slot<?>> list = planOrder.get(kind);
            copy.put(kind, Collections.unmodifiableList(list == null ? new ArrayList<>() : new ArrayList<>(list)));
        }
        this.planOrder = copy;
    }

    /** Compiles with the contract's own check. */
    public static Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        return StandardCompiler.INSTANCE.compile(transitions, slots);
    }

    /** Compiles with the contract's own check, or throws {@link RegistryRejected} with every diagnostic. */
    public static SlotRegistry of(Transitions transitions, Collection<Slot<?>> slots) {
        Compilation result = compile(transitions, slots);
        if (result instanceof Compilation.Rejected rejected) {
            throw new RegistryRejected(rejected.diagnostics());
        }
        return ((Compilation.Accepted) result).registry();
    }

    public Transitions transitions() {
        return transitions;
    }

    /** Every slot, ordered by id. */
    public List<Slot<?>> slots() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    public Slot<?> slot(SlotId id) {
        Slot<?> slot = byId.get(id);
        if (slot == null) {
            throw new IllegalArgumentException("unknown slot " + id);
        }
        return slot;
    }

    /** The slots of one scope, in the order a plan visits them. */
    public List<Slot<?>> slotsFor(HostKind scope) {
        return planOrder.get(scope);
    }

    /** Thrown by {@link #of} when the declaration is not accepted. */
    public static final class RegistryRejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final transient List<Diagnostic> diagnostics;

        RegistryRejected(List<Diagnostic> diagnostics) {
            super(describe(diagnostics));
            this.diagnostics = diagnostics;
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }

        private static String describe(List<Diagnostic> diagnostics) {
            StringBuilder b = new StringBuilder("the slot declaration is not accepted:");
            for (Diagnostic d : diagnostics) {
                b.append("\n  ").append(d);
            }
            return b.toString();
        }
    }
}
