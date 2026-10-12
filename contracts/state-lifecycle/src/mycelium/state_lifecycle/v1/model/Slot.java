package mycelium.state_lifecycle.v1.model;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A declared piece of state: where it lives (scope), whether it is saved (durability), the codec of a saved one, the
 * value an absent slot reads as (initial), what it does at every transition (policies), and the slots whose effects
 * must come before its own (dependencies). Values are immutable: a slot never holds something a caller can change.
 *
 * <p>A slot is checked only when it joins a registry ({@link SlotRegistry#compile}); the builder accepts anything, so
 * that the check can name what is wrong.
 */
public final class Slot<T> {
    private final SlotId id;
    private final HostKind scope;
    private final Durability durability;
    private final SlotCodec<T> codec;
    private final T initial;
    private final Map<TransitionId, Policy<T>> policies;
    private final Set<SlotId> dependencies;

    private Slot(Builder<T> b) {
        this.id = b.id;
        this.scope = b.scope;
        this.durability = b.durability;
        this.codec = b.codec;
        this.initial = b.initial;
        this.policies = Collections.unmodifiableMap(new TreeMap<>(b.policies));
        this.dependencies = Collections.unmodifiableSet(new TreeSet<>(b.dependencies));
    }

    public static <T> Builder<T> builder(SlotId id, HostKind scope, Durability durability, T initial) {
        return new Builder<>(id, scope, durability, initial);
    }

    public static <T> Builder<T> session(String id, HostKind scope, T initial) {
        return new Builder<>(new SlotId(id), scope, Durability.SESSION, initial);
    }

    public static <T> Builder<T> durable(String id, HostKind scope, T initial, SlotCodec<T> codec) {
        return new Builder<>(new SlotId(id), scope, Durability.DURABLE, initial).codec(codec);
    }

    /** A builder holding everything this slot declares, to change a part of it. */
    public Builder<T> toBuilder() {
        Builder<T> b = new Builder<>(id, scope, durability, initial);
        b.codec = codec;
        b.policies.putAll(policies);
        b.dependencies.addAll(dependencies);
        return b;
    }

    public SlotId id() {
        return id;
    }

    public HostKind scope() {
        return scope;
    }

    public Durability durability() {
        return durability;
    }

    public Optional<SlotCodec<T>> codec() {
        return Optional.ofNullable(codec);
    }

    public T initial() {
        return initial;
    }

    public Optional<Policy<T>> policy(TransitionId transition) {
        return Optional.ofNullable(policies.get(transition));
    }

    /** Every declared policy, ordered by transition id. */
    public Map<TransitionId, Policy<T>> policies() {
        return policies;
    }

    public Set<SlotId> dependencies() {
        return dependencies;
    }

    /**
     * True when the slot declares EXCLUSIVE anywhere: its value then exists on at most one host (law L3), which restricts
     * what else it may declare (the check in {@link SlotRegistry#compile}) and refuses a second holder in
     * {@link HostStore#materialize} and {@link HostStore#write}.
     */
    public boolean exclusive() {
        for (Policy<T> p : policies.values()) {
            if (p.kind() == Policy.Kind.EXCLUSIVE) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    T cast(Object value) {
        return (T) value;
    }

    Object encodeValue(Object value) {
        return codec.encode(cast(value));
    }

    Object decodeValue(Object stored) throws SlotCodecException {
        return codec.decode(stored);
    }

    Object migrateValue(TransitionId at, Object old) {
        return policies.get(at).migrated(cast(old));
    }

    Object regenerateValue(TransitionId at, HostId host) {
        return policies.get(at).regenerated(host);
    }

    @Override
    public String toString() {
        return id.toString();
    }

    public static final class Builder<T> {
        private final SlotId id;
        private final HostKind scope;
        private final Durability durability;
        private final T initial;
        private SlotCodec<T> codec;
        private final Map<TransitionId, Policy<T>> policies = new TreeMap<>();
        private final Set<SlotId> dependencies = new TreeSet<>();

        private Builder(SlotId id, HostKind scope, Durability durability, T initial) {
            this.id = Objects.requireNonNull(id, "id");
            this.scope = Objects.requireNonNull(scope, "scope");
            this.durability = Objects.requireNonNull(durability, "durability");
            this.initial = Objects.requireNonNull(initial, "initial");
        }

        public Builder<T> codec(SlotCodec<T> codec) {
            this.codec = codec;
            return this;
        }

        /** Declares the policy at one transition. Declaring the same transition again replaces the earlier policy. */
        public Builder<T> on(TransitionId transition, Policy<T> policy) {
            policies.put(Objects.requireNonNull(transition, "transition"), Objects.requireNonNull(policy, "policy"));
            return this;
        }

        /** Declares the same policy at each transition of a group ({@link Transitions#shaped}). Later calls replace earlier. */
        public Builder<T> on(Collection<TransitionId> transitions, Policy<T> policy) {
            for (TransitionId t : transitions) {
                on(t, policy);
            }
            return this;
        }

        public Builder<T> dependsOn(SlotId... slots) {
            for (SlotId s : slots) {
                dependencies.add(Objects.requireNonNull(s, "slot"));
            }
            return this;
        }

        public Slot<T> build() {
            return new Slot<>(this);
        }
    }
}
