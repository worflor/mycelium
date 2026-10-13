package mycelium.state_lifecycle.v1.fixtures;

import mycelium.state_lifecycle.v1.model.Compilation;
import mycelium.state_lifecycle.v1.model.Diagnostic;
import mycelium.state_lifecycle.v1.model.Durability;
import mycelium.state_lifecycle.v1.model.Policy;
import mycelium.state_lifecycle.v1.model.RegistryCompiler;
import mycelium.state_lifecycle.v1.model.Shape;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotCodec;
import mycelium.state_lifecycle.v1.model.SlotCodecException;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.StandardCompiler;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Transitions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * A declaration check with one hole: it accepts one kind of bad declaration the contract refuses. Each {@link Lapse} is a
 * mistake an implementer makes when writing the check for a real mod's slots (a check that forgets a case, or accepts
 * what it can make sense of). The lapse is applied by repairing the declaration the way a lenient check would read it
 * (an unknown transition is ignored, a policy that means nothing is read as keep) and compiling what is left with the
 * reference check, so every other problem is still reported.
 */
public final class LaxCompiler implements RegistryCompiler {
    public enum Lapse {
        /** A policy for a transition nobody declared is ignored: a typo in a transition id declares nothing. */
        IGNORES_UNKNOWN_TRANSITION,
        /** A policy for a transition that does not apply to the slot's scope is ignored. */
        IGNORES_OUT_OF_SCOPE_POLICY,
        /** COPY is accepted on a transition that creates no host, and read as keep. */
        COPY_ANYWHERE,
        /** REGENERATE is accepted on a transition that creates no host, and read as keep. */
        REGENERATE_ANYWHERE,
        /** EXCLUSIVE is accepted on a transition that creates no host, and read as keep. */
        EXCLUSIVE_ANYWHERE,
        /** A session slot may keep its value past the end of an incarnation. */
        SESSION_KEPT_PAST_END,
        /** A durable slot without a codec is accepted, and gets a pass-through one. */
        CODECLESS_DURABLE,
        /** Slots in a dependency cycle are accepted, their dependencies silently dropped. */
        BLIND_TO_CYCLES,
        /** A dependency on a slot nobody declared is ignored. */
        BLIND_TO_UNKNOWN_DEPENDENCY,
        /** A dependency on a slot of another scope is ignored. */
        BLIND_TO_CROSS_SCOPE_DEPENDENCY,
        /** A second declaration of a slot id is dropped without a word: the first stays. */
        SILENT_DUPLICATE,
        /** An exclusive slot may RESET, REGENERATE, COPY (or migrate at a fork): the extra policy is read as keep or drop. */
        EXCLUSIVE_MAY_CREATE
    }

    private final Lapse lapse;

    public LaxCompiler(Lapse lapse) {
        this.lapse = lapse;
    }

    @Override
    public Compilation compile(Transitions transitions, Collection<Slot<?>> slots) {
        List<Slot<?>> repaired = repair(transitions, new ArrayList<>(slots));
        return StandardCompiler.INSTANCE.compile(transitions, repaired);
    }

    private List<Slot<?>> repair(Transitions transitions, List<Slot<?>> slots) {
        switch (lapse) {
            case SILENT_DUPLICATE: {
                Map<SlotId, Slot<?>> first = new LinkedHashMap<>();
                for (Slot<?> s : slots) {
                    first.putIfAbsent(s.id(), s);
                }
                return new ArrayList<>(first.values());
            }
            case IGNORES_UNKNOWN_TRANSITION:
                return rewrite(slots, (s, at, p) -> transitions.contains(at) ? p : null, false);
            case IGNORES_OUT_OF_SCOPE_POLICY:
                return rewrite(slots, (s, at, p) -> transitions.contains(at) && !transitions.get(at).admits(s.scope()) ? null : p,
                        false);
            case COPY_ANYWHERE:
                return rewrite(slots, (s, at, p) -> p.kind() == Policy.Kind.COPY && !forks(transitions, at)
                        ? neutral(transitions, at) : p, false);
            case REGENERATE_ANYWHERE:
                return rewrite(slots, (s, at, p) -> p.kind() == Policy.Kind.REGENERATE && !forks(transitions, at)
                        ? neutral(transitions, at) : p, false);
            case EXCLUSIVE_ANYWHERE:
                return rewrite(slots, (s, at, p) -> p.kind() == Policy.Kind.EXCLUSIVE && !forks(transitions, at)
                        ? neutral(transitions, at) : p, false);
            case SESSION_KEPT_PAST_END:
                return rewrite(slots, (s, at, p) -> s.durability() == Durability.SESSION && p.kind() == Policy.Kind.KEEP
                        && transitions.contains(at) && transitions.get(at).shape() == Shape.ENDS ? Policy.drop() : p, false);
            case CODECLESS_DURABLE:
                return rewrite(slots, (s, at, p) -> p, true);
            case BLIND_TO_CYCLES: {
                Set<SlotId> stuck = new HashSet<>();
                if (StandardCompiler.INSTANCE.compile(transitions, slots) instanceof Compilation.Rejected r) {
                    for (Diagnostic d : r.diagnostics()) {
                        if (d.code() == Diagnostic.Code.DEPENDENCY_CYCLE) {
                            stuck.add(d.slot());
                        }
                    }
                }
                return rewriteDeps(slots, (s, dep) -> !stuck.contains(s.id()));
            }
            case BLIND_TO_UNKNOWN_DEPENDENCY: {
                Set<SlotId> known = new HashSet<>();
                for (Slot<?> s : slots) {
                    known.add(s.id());
                }
                return rewriteDeps(slots, (s, dep) -> known.contains(dep));
            }
            case BLIND_TO_CROSS_SCOPE_DEPENDENCY: {
                Map<SlotId, Slot<?>> byId = new TreeMap<>();
                for (Slot<?> s : slots) {
                    byId.putIfAbsent(s.id(), s);
                }
                return rewriteDeps(slots, (s, dep) -> !byId.containsKey(dep) || byId.get(dep).scope() == s.scope());
            }
            case EXCLUSIVE_MAY_CREATE:
                return rewrite(slots, (s, at, p) -> s.exclusive() && (p.kind() == Policy.Kind.RESET
                        || p.kind() == Policy.Kind.REGENERATE || p.kind() == Policy.Kind.COPY
                        || (p.kind() == Policy.Kind.MIGRATE && forks(transitions, at))) ? neutral(transitions, at) : p, false);
            default:
                throw new IllegalStateException(lapse.name());
        }
    }

    private interface PolicyRewrite {
        /** The policy to declare instead, or null for none. */
        Policy<?> apply(Slot<?> slot, TransitionId at, Policy<?> policy);
    }

    private interface DepFilter {
        boolean keep(Slot<?> slot, SlotId dependency);
    }

    private static boolean forks(Transitions transitions, TransitionId at) {
        return transitions.contains(at) && transitions.get(at).shape() == Shape.FORKS;
    }

    /** What a lenient check reads a meaningless policy as: the value stays, or goes where it must (forks, ends). */
    private static Policy<?> neutral(Transitions transitions, TransitionId at) {
        Shape shape = transitions.contains(at) ? transitions.get(at).shape() : Shape.STAYS;
        return shape == Shape.FORKS || shape == Shape.ENDS ? Policy.drop() : Policy.keep();
    }

    private static List<Slot<?>> rewriteDeps(List<Slot<?>> slots, DepFilter filter) {
        List<Slot<?>> out = new ArrayList<>();
        for (Slot<?> s : slots) {
            out.add(rebuild(s, (slot, at, p) -> p, dep -> filter.keep(s, dep), false));
        }
        return out;
    }

    private static List<Slot<?>> rewrite(List<Slot<?>> slots, PolicyRewrite rewrite, boolean fixCodec) {
        List<Slot<?>> out = new ArrayList<>();
        for (Slot<?> s : slots) {
            out.add(rebuild(s, rewrite, dep -> true, fixCodec));
        }
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Slot<?> rebuild(Slot<?> slot, PolicyRewrite rewrite, Predicate<SlotId> keepDep, boolean fixCodec) {
        Slot.Builder b = Slot.builder(slot.id(), slot.scope(), slot.durability(), slot.initial());
        if (slot.codec().isPresent()) {
            b.codec((SlotCodec) slot.codec().get());
        } else if (fixCodec && slot.durability() == Durability.DURABLE) {
            b.codec(PASS_THROUGH);
        }
        Map<TransitionId, Policy<?>> policies = (Map<TransitionId, Policy<?>>) (Map) slot.policies();
        for (Map.Entry<TransitionId, Policy<?>> e : policies.entrySet()) {
            Policy<?> p = rewrite.apply(slot, e.getKey(), e.getValue());
            if (p != null) {
                b.on(e.getKey(), (Policy) p);
            }
        }
        for (SlotId dep : slot.dependencies()) {
            if (keepDep.test(dep)) {
                b.dependsOn(dep);
            }
        }
        return b.build();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final SlotCodec PASS_THROUGH = new SlotCodec() {
        @Override
        public Object encode(Object value) {
            return value;
        }

        @Override
        public Object decode(Object stored) throws SlotCodecException {
            return stored;
        }

        @Override
        public boolean same(Object a, Object b) {
            return a.equals(b);
        }
    };
}
