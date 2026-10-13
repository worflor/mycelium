package mycelium.state_lifecycle.v1.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The transitions a registry is checked against: the platform's, plus the consumer's own. Immutable, ordered by id.
 *
 * <p>The platform set and what each does:
 * <pre>
 * mycelium:save              SAVES     every kind
 * mycelium:load              BEGINS    entity, level, server
 * mycelium:login             BEGINS    player
 * mycelium:logout            ENDS      player
 * mycelium:unload            ENDS      entity, level
 * mycelium:remove            ENDS      entity
 * mycelium:server_stop       ENDS      every kind
 * mycelium:death             STAYS     player, entity
 * mycelium:dimension_change  STAYS     player, entity
 * mycelium:respawn           SUCCEEDS  player
 * mycelium:clone             FORKS     player, entity
 * </pre>
 * A loader that replaces an entity's object on a dimension change reports it as an unload and a load of the same host,
 * or as a consumer transition of the shape it really has.
 */
public final class Transitions {
    private final Map<TransitionId, Transition> byId;

    private Transitions(Map<TransitionId, Transition> byId) {
        this.byId = Collections.unmodifiableMap(new TreeMap<>(byId));
    }

    /** The platform's transitions only. */
    public static Transitions platform() {
        return builder().build();
    }

    /** A builder that starts from the platform's transitions. */
    public static Builder builder() {
        Builder b = new Builder();
        b.add(TransitionId.SAVE, Shape.SAVES, EnumSet.allOf(HostKind.class));
        b.add(TransitionId.LOAD, Shape.BEGINS, EnumSet.of(HostKind.ENTITY, HostKind.LEVEL, HostKind.SERVER));
        b.add(TransitionId.LOGIN, Shape.BEGINS, EnumSet.of(HostKind.PLAYER));
        b.add(TransitionId.LOGOUT, Shape.ENDS, EnumSet.of(HostKind.PLAYER));
        b.add(TransitionId.UNLOAD, Shape.ENDS, EnumSet.of(HostKind.ENTITY, HostKind.LEVEL));
        b.add(TransitionId.REMOVE, Shape.ENDS, EnumSet.of(HostKind.ENTITY));
        b.add(TransitionId.SERVER_STOP, Shape.ENDS, EnumSet.allOf(HostKind.class));
        b.add(TransitionId.DEATH, Shape.STAYS, EnumSet.of(HostKind.PLAYER, HostKind.ENTITY));
        b.add(TransitionId.DIMENSION_CHANGE, Shape.STAYS, EnumSet.of(HostKind.PLAYER, HostKind.ENTITY));
        b.add(TransitionId.RESPAWN, Shape.SUCCEEDS, EnumSet.of(HostKind.PLAYER));
        b.add(TransitionId.CLONE, Shape.FORKS, EnumSet.of(HostKind.PLAYER, HostKind.ENTITY));
        return b;
    }

    public Transition get(TransitionId id) {
        Transition t = byId.get(id);
        if (t == null) {
            throw new IllegalArgumentException("unknown transition " + id);
        }
        return t;
    }

    public boolean contains(TransitionId id) {
        return byId.containsKey(id);
    }

    /** Every transition, ordered by id. */
    public List<Transition> all() {
        return Collections.unmodifiableList(new ArrayList<>(byId.values()));
    }

    /** The ids of the transitions that apply to a host kind, ordered. */
    public Set<TransitionId> admitting(HostKind kind) {
        Set<TransitionId> out = new LinkedHashSet<>();
        for (Transition t : byId.values()) {
            if (t.admits(kind)) {
                out.add(t.id());
            }
        }
        return out;
    }

    /** The ids of the transitions of any of the given shapes, ordered. For declaring one policy across a group. */
    public Set<TransitionId> shaped(Shape... shapes) {
        Set<Shape> wanted = EnumSet.noneOf(Shape.class);
        wanted.addAll(Arrays.asList(shapes));
        Set<TransitionId> out = new LinkedHashSet<>();
        for (Transition t : byId.values()) {
            if (wanted.contains(t.shape())) {
                out.add(t.id());
            }
        }
        return out;
    }

    /**
     * The ids of the transitions of any of the given shapes that apply to hosts of {@code scope}, ordered: the group a slot of that scope
     * declares one policy across. {@link #shaped(Shape...)} alone also names the transitions of other kinds of host (an unload is not a
     * player's), and a slot that declares a policy for one is refused (law L1). Learned in the lifecycle bridge: a mod declares policies
     * by shape, for its own scope, and this is that group.
     */
    public Set<TransitionId> shaped(HostKind scope, Shape... shapes) {
        Set<TransitionId> out = new LinkedHashSet<>();
        for (TransitionId id : shaped(shapes)) {
            if (byId.get(id).admits(scope)) {
                out.add(id);
            }
        }
        return out;
    }

    public static final class Builder {
        private final Map<TransitionId, Transition> byId = new TreeMap<>();

        private Builder() {
        }

        private void add(TransitionId id, Shape shape, Set<HostKind> kinds) {
            if (byId.containsKey(id)) {
                throw new IllegalArgumentException("transition " + id + " is declared twice");
            }
            byId.put(id, new Transition(id, shape, kinds));
        }

        /** Declares a consumer's transition. The {@code mycelium} namespace is the platform's. */
        public Builder declare(String id, Shape shape, HostKind first, HostKind... rest) {
            TransitionId tid = new TransitionId(id);
            if (tid.isPlatform()) {
                throw new IllegalArgumentException("the " + TransitionId.PLATFORM_NAMESPACE
                        + " namespace is the platform's: cannot declare " + id);
            }
            add(tid, shape, EnumSet.of(first, rest));
            return this;
        }

        public Transitions build() {
            return new Transitions(byId);
        }
    }
}
