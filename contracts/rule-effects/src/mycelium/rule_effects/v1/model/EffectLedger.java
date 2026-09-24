package mycelium.rule_effects.v1.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable record of which rule owns which ids on which entity. Pure: it never touches a port except in
 * {@link #reconcile}, which only reads. The law it encodes: every apply has a matching remove.
 */
public record EffectLedger(Map<EntityId, Map<String, Set<String>>> entries) {
    public EffectLedger {
        Map<EntityId, Map<String, Set<String>>> copy = new LinkedHashMap<>();
        for (Map.Entry<EntityId, Map<String, Set<String>>> e : entries.entrySet()) {
            Map<String, Set<String>> rules = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> r : e.getValue().entrySet()) {
                rules.put(r.getKey(), Set.copyOf(r.getValue()));
            }
            copy.put(e.getKey(), Map.copyOf(rules));
        }
        entries = Map.copyOf(copy);
    }

    public static EffectLedger empty() {
        return new EffectLedger(Map.of());
    }

    public EffectLedger withApplied(Receipt.ApplyReceipt receipt) {
        Map<EntityId, Map<String, Set<String>>> next = mutableCopy();
        for (EntityId entity : receipt.entities()) {
            Map<String, Set<String>> rules = next.computeIfAbsent(entity, k -> new LinkedHashMap<>());
            Set<String> ids = new LinkedHashSet<>(rules.getOrDefault(receipt.ruleId(), Set.of()));
            ids.addAll(receipt.ids());
            rules.put(receipt.ruleId(), ids);
        }
        return new EffectLedger(next);
    }

    public EffectLedger withRemoved(Receipt.RemoveReceipt receipt) {
        Map<EntityId, Map<String, Set<String>>> next = mutableCopy();
        for (EntityId entity : receipt.entities()) {
            Map<String, Set<String>> rules = next.get(entity);
            if (rules == null) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>(rules.getOrDefault(receipt.ruleId(), Set.of()));
            ids.removeAll(receipt.ids());
            if (ids.isEmpty()) {
                rules.remove(receipt.ruleId());
            } else {
                rules.put(receipt.ruleId(), ids);
            }
            if (rules.isEmpty()) {
                next.remove(entity);
            }
        }
        return new EffectLedger(next);
    }

    public Set<String> idsFor(EntityId entity, String ruleId) {
        Map<String, Set<String>> rules = entries.get(entity);
        if (rules == null) {
            return Set.of();
        }
        return rules.getOrDefault(ruleId, Set.of());
    }

    public Set<String> allIds(EntityId entity) {
        Map<String, Set<String>> rules = entries.get(entity);
        if (rules == null) {
            return Set.of();
        }
        Set<String> all = new LinkedHashSet<>();
        for (Set<String> ids : rules.values()) {
            all.addAll(ids);
        }
        return all;
    }

    /**
     * Compares the ledger against the port for the given entities. An empty result means every apply the
     * ledger knows about is still active and nothing is active that the ledger does not own.
     */
    public static List<Leak> reconcile(EffectLedger ledger, EffectPort port, Set<EntityId> entities) {
        List<Leak> leaks = new ArrayList<>();
        for (EntityId entity : entities) {
            Set<String> owned = ledger.allIds(entity);
            Set<String> active = port.active(entity);
            for (String id : active) {
                if (!owned.contains(id)) {
                    leaks.add(new Leak(entity, id, Leak.Kind.ORPHAN_ON_PORT));
                }
            }
            for (String id : owned) {
                if (!active.contains(id)) {
                    leaks.add(new Leak(entity, id, Leak.Kind.MISSING_ON_PORT));
                }
            }
        }
        return leaks;
    }

    private Map<EntityId, Map<String, Set<String>>> mutableCopy() {
        Map<EntityId, Map<String, Set<String>>> next = new LinkedHashMap<>();
        for (Map.Entry<EntityId, Map<String, Set<String>>> e : entries.entrySet()) {
            Map<String, Set<String>> rules = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> r : e.getValue().entrySet()) {
                rules.put(r.getKey(), new LinkedHashSet<>(r.getValue()));
            }
            next.put(e.getKey(), rules);
        }
        return next;
    }
}
