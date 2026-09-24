package mycelium.rule_effects.v1.fixtures;

import mycelium.rule_effects.v1.model.EffectPort;
import mycelium.rule_effects.v1.model.EffectSpec;
import mycelium.rule_effects.v1.model.EntityId;
import mycelium.rule_effects.v1.model.Tx;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Reference in-memory port: ids are a set per entity; apply with an existing id replaces; every mutation registers an undo. */
public class MemoryEffectPort implements EffectPort {
    protected final Map<EntityId, Set<String>> active = new LinkedHashMap<>();

    @Override
    public boolean apply(EntityId entity, EffectSpec effect, Tx tx) {
        Set<String> ids = active.computeIfAbsent(entity, k -> new LinkedHashSet<>());
        boolean added = ids.add(effect.id());
        if (added) {
            tx.registerUndo(() -> ids.remove(effect.id()));
        }
        return true;
    }

    @Override
    public boolean remove(EntityId entity, String id, Tx tx) {
        Set<String> ids = active.get(entity);
        if (ids == null) {
            return true;
        }
        boolean removed = ids.remove(id);
        if (removed) {
            tx.registerUndo(() -> ids.add(id));
        }
        return true;
    }

    @Override
    public Set<String> active(EntityId entity) {
        Set<String> ids = active.get(entity);
        return ids == null ? Set.of() : Set.copyOf(ids);
    }

    @Override
    public void know(EntityId entity) {
        active.computeIfAbsent(entity, k -> new LinkedHashSet<>());
    }

    protected Set<EntityId> known() {
        return Set.copyOf(active.keySet());
    }
}
