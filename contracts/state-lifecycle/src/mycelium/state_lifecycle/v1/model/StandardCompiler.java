package mycelium.state_lifecycle.v1.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The contract's declaration check. It reports every problem it finds, each naming the slot and the transition, and
 * accepts only a declaration with none:
 * <ul>
 *   <li>L1: every slot has a policy for every transition its scope admits, and none for any other;</li>
 *   <li>every policy is admissible on its transition's shape ({@link Shape#admits}): COPY, REGENERATE and EXCLUSIVE only
 *       where a host is created, a session value dropped at every end;</li>
 *   <li>an EXCLUSIVE slot declares nothing that would put its value on a second host (RESET, REGENERATE, COPY, or MIGRATE
 *       at a fork); at a fork it is EXCLUSIVE or DROP;</li>
 *   <li>a durable slot has a codec;</li>
 *   <li>slot ids are unique; dependencies name slots of the same scope; dependencies are acyclic.</li>
 * </ul>
 */
public final class StandardCompiler implements RegistryCompiler {
    public static final StandardCompiler INSTANCE = new StandardCompiler();

    private StandardCompiler() {
    }

    @Override
    public Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        List<Diagnostic> found = new ArrayList<>();
        Map<SlotId, Slot<?>> byId = new TreeMap<>();
        for (Slot<?> slot : slots) {
            if (byId.containsKey(slot.id())) {
                found.add(new Diagnostic(Diagnostic.Code.DUPLICATE_SLOT, slot.id(), null,
                        "slot '" + slot.id() + "' is declared twice"));
            } else {
                byId.put(slot.id(), slot);
            }
        }
        for (Slot<?> slot : byId.values()) {
            checkSlot(transitions, slot, found);
            checkDependencies(slot, byId, found);
        }
        Map<HostKind, List<Slot<?>>> order = new EnumMap<>(HostKind.class);
        for (HostKind kind : HostKind.values()) {
            order.put(kind, order(kind, byId, found));
        }
        if (!found.isEmpty()) {
            Collections.sort(found);
            return new Compilation.Rejected(found);
        }
        return new Compilation.Accepted(new SlotRegistry(transitions, byId, order));
    }

    private static void checkSlot(Transitions transitions, Slot<?> slot, List<Diagnostic> found) {
        if (slot.durability() == Durability.DURABLE && !slot.codec().isPresent()) {
            found.add(new Diagnostic(Diagnostic.Code.DURABLE_WITHOUT_CODEC, slot.id(), null,
                    "durable slot '" + slot.id() + "' has no codec"));
        }
        for (TransitionId id : slot.policies().keySet()) {
            if (!transitions.contains(id)) {
                found.add(new Diagnostic(Diagnostic.Code.UNKNOWN_TRANSITION, slot.id(), id,
                        "slot '" + slot.id() + "' declares a policy for transition '" + id + "', which is not declared"));
            } else if (!transitions.get(id).admits(slot.scope())) {
                found.add(new Diagnostic(Diagnostic.Code.OUT_OF_SCOPE_POLICY, slot.id(), id,
                        "slot '" + slot.id() + "' (" + slot.scope() + ") declares a policy for transition '" + id
                                + "', which does not apply to " + slot.scope() + " hosts"));
            }
        }
        for (Transition t : transitions.all()) {
            if (!t.admits(slot.scope())) {
                continue;
            }
            Policy<?> policy = slot.policies().get(t.id());
            if (policy == null) {
                found.add(new Diagnostic(Diagnostic.Code.MISSING_POLICY, slot.id(), t.id(),
                        "slot '" + slot.id() + "' declares no policy for transition '" + t.id() + "' (" + t.shape() + ")"));
                continue;
            }
            Policy.Kind kind = policy.kind();
            if (!t.shape().admits(slot.durability(), kind)) {
                String why = kind == Policy.Kind.COPY || kind == Policy.Kind.REGENERATE || kind == Policy.Kind.EXCLUSIVE
                        ? kind + " applies only to transitions that create a host, and '" + t.id() + "' is " + t.shape()
                        : kind + " has no meaning for a " + slot.durability() + " slot on '" + t.id() + "' (" + t.shape() + ")";
                found.add(new Diagnostic(Diagnostic.Code.INADMISSIBLE_POLICY, slot.id(), t.id(),
                        "slot '" + slot.id() + "': " + why));
            }
        }
        if (slot.exclusive()) {
            for (TransitionId at : slot.policies().keySet()) {
                Policy.Kind kind = slot.policies().get(at).kind();
                boolean creates = kind == Policy.Kind.RESET || kind == Policy.Kind.REGENERATE || kind == Policy.Kind.COPY
                        || (kind == Policy.Kind.MIGRATE && transitions.contains(at)
                                && transitions.get(at).shape() == Shape.FORKS);
                if (creates) {
                    found.add(new Diagnostic(Diagnostic.Code.EXCLUSIVE_CREATES_VALUE, slot.id(), at,
                            "slot '" + slot.id() + "' is EXCLUSIVE, so " + kind + " at '" + at
                                    + "' would put its value on a second host"));
                }
            }
        }
    }

    private static void checkDependencies(Slot<?> slot, Map<SlotId, Slot<?>> byId, List<Diagnostic> found) {
        for (SlotId dep : slot.dependencies()) {
            Slot<?> target = byId.get(dep);
            if (target == null) {
                found.add(new Diagnostic(Diagnostic.Code.UNKNOWN_DEPENDENCY, slot.id(), null,
                        "slot '" + slot.id() + "' depends on '" + dep + "', which is not declared"));
            } else if (target.scope() != slot.scope()) {
                found.add(new Diagnostic(Diagnostic.Code.CROSS_SCOPE_DEPENDENCY, slot.id(), null,
                        "slot '" + slot.id() + "' (" + slot.scope() + ") depends on '" + dep + "' (" + target.scope()
                                + "): a plan orders the slots of one host"));
            }
        }
    }

    /** Dependencies first, then id: Kahn's algorithm taking the smallest ready id each time. */
    private static List<Slot<?>> order(HostKind kind, Map<SlotId, Slot<?>> byId, List<Diagnostic> found) {
        Map<SlotId, Slot<?>> scope = new TreeMap<>();
        for (Slot<?> slot : byId.values()) {
            if (slot.scope() == kind) {
                scope.put(slot.id(), slot);
            }
        }
        Map<SlotId, Integer> waiting = new HashMap<>();
        Map<SlotId, List<SlotId>> dependents = new HashMap<>();
        for (Slot<?> slot : scope.values()) {
            int n = 0;
            for (SlotId dep : slot.dependencies()) {
                if (scope.containsKey(dep)) {
                    n++;
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(slot.id());
                }
            }
            waiting.put(slot.id(), n);
        }
        TreeSet<SlotId> ready = new TreeSet<>();
        for (Map.Entry<SlotId, Integer> e : waiting.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<Slot<?>> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            SlotId next = ready.pollFirst();
            out.add(scope.get(next));
            for (SlotId d : dependents.getOrDefault(next, Collections.<SlotId>emptyList())) {
                int left = waiting.get(d) - 1;
                waiting.put(d, left);
                if (left == 0) {
                    ready.add(d);
                }
            }
        }
        if (out.size() < scope.size()) {
            TreeSet<SlotId> stuck = new TreeSet<>(scope.keySet());
            for (Slot<?> done : out) {
                stuck.remove(done.id());
            }
            for (SlotId id : stuck) {
                found.add(new Diagnostic(Diagnostic.Code.DEPENDENCY_CYCLE, id, null,
                        "slot '" + id + "' is on or behind a dependency cycle among " + stuck));
            }
        }
        return out;
    }
}
