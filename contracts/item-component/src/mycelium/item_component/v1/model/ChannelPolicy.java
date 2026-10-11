package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Channel projection policy for specializing item snapshots across operational boundaries:
 * <ul>
 *     <li>{@link #PERSISTENT}: Full disk persistence (retains all foreign/unmapped/quarantined tags).</li>
 *     <li>{@link #NETWORK}: Client-bound networking (filters server-internal components, diagnostics, and transient tags).</li>
 *     <li>{@link #EFFECTIVE}: Live gameplay interaction state (resolves prototype baselines against stack overrides and removals).</li>
 *     <li>{@link #IDENTITY}: Stacking equivalence: items match if and only if their effective identity state and meaningful retained evidence match.</li>
 * </ul>
 */
public enum ChannelPolicy {
    PERSISTENT {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
            return Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b, ComponentRegistry registry, ItemPrototype prototype) {
            return Objects.equals(a, b);
        }
    },

    NETWORK {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(registry, "registry");

            // Filter typed components: omit server-internal or non-network-synced components
            Map<ComponentId, Object> filteredComponents = new TreeMap<>();
            for (Map.Entry<ComponentId, Object> e : snapshot.components().entrySet()) {
                ComponentId id = e.getKey();
                boolean synced = true;
                Optional<ComponentDefinition<?>> defOpt = registry.get(id);
                if (defOpt.isPresent()) {
                    synced = defOpt.get().networkSynced();
                } else if (id.namespace().equals("server_internal") || id.toString().startsWith("server_internal:")) {
                    synced = false;
                }
                if (synced) {
                    filteredComponents.put(id, e.getValue());
                }
            }

            // Filter unmapped tags: omit server-internal tags
            Map<String, TagNode> filteredUnmapped = new TreeMap<>();
            for (Map.Entry<String, TagNode> e : snapshot.unmappedTags().entrySet()) {
                if (!e.getKey().startsWith("server_internal:") && !e.getKey().startsWith("server_internal.")) {
                    filteredUnmapped.put(e.getKey(), e.getValue());
                }
            }

            // Filter removals: omit server-internal removals
            Set<ComponentId> filteredRemovals = new TreeSet<>();
            for (ComponentId rem : snapshot.removals()) {
                if (!rem.namespace().equals("server_internal")) {
                    filteredRemovals.add(rem);
                }
            }

            return new CarrierSnapshot(filteredComponents, filteredUnmapped, filteredRemovals,
                    Collections.emptyList(), Optional.empty(), snapshot.retainedResiduals());
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b, ComponentRegistry registry, ItemPrototype prototype) {
            return Objects.equals(project(a, registry, prototype), project(b, registry, prototype));
        }
    },

    EFFECTIVE {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(prototype, "prototype");

            Map<ComponentId, Object> effective = new TreeMap<>();

            // 1. Seed with prototype base components
            for (Map.Entry<ComponentId, Object> e : prototype.baseComponents().entrySet()) {
                effective.put(e.getKey(), e.getValue());
            }

            // 2. Apply removals (drops from effective state)
            for (ComponentId rem : snapshot.removals()) {
                effective.remove(rem);
            }

            // 3. Apply stack overrides
            for (Map.Entry<ComponentId, Object> e : snapshot.components().entrySet()) {
                effective.put(e.getKey(), e.getValue());
            }

            return new CarrierSnapshot(effective, Collections.emptyMap(), Collections.emptySet(),
                    Collections.emptyList(), Optional.empty(), Collections.emptyMap());
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b, ComponentRegistry registry, ItemPrototype prototype) {
            CarrierSnapshot effA = project(a, registry, prototype);
            CarrierSnapshot effB = project(b, registry, prototype);
            return ValueSemantics.deepEquals(effA.components(), effB.components());
        }
    },

    IDENTITY {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype) {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(prototype, "prototype");

            // 1. Compute effective state
            CarrierSnapshot eff = EFFECTIVE.project(snapshot, registry, prototype);

            // 2. Filter components that do not affect identity
            Map<ComponentId, Object> identityComponents = new TreeMap<>();
            for (Map.Entry<ComponentId, Object> e : eff.components().entrySet()) {
                ComponentId id = e.getKey();
                boolean affectsId = registry.get(id).map(ComponentDefinition::affectsIdentity).orElse(true);
                if (affectsId) {
                    identityComponents.put(id, e.getValue());
                }
            }

            // 3. Retain meaningful unmapped and recovery data (unreadable envelopes, foreign tags)
            Map<String, TagNode> cleanUnmapped = new TreeMap<>();
            for (Map.Entry<String, TagNode> e : snapshot.unmappedTags().entrySet()) {
                if (!e.getKey().startsWith("server_internal:")) {
                    cleanUnmapped.put(e.getKey(), e.getValue());
                }
            }

            List<RecoveryRecord> meaningfulRecovery = new ArrayList<>();
            for (RecoveryRecord rec : snapshot.recoveryRecords()) {
                // Keep corrupt payloads and unreadable envelopes as meaningful differences
                if (rec.category() == RecoveryRecord.FailureCategory.CORRUPT_PAYLOAD ||
                    rec.category() == RecoveryRecord.FailureCategory.UNREADABLE_ENVELOPE ||
                    rec.category() == RecoveryRecord.FailureCategory.ALIAS_COLLISION) {
                    meaningfulRecovery.add(rec);
                }
            }

            return new CarrierSnapshot(identityComponents, cleanUnmapped, Collections.emptySet(),
                    meaningfulRecovery, snapshot.rawEnvelope(), Collections.emptyMap());
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b, ComponentRegistry registry, ItemPrototype prototype) {
            return Objects.equals(project(a, registry, prototype), project(b, registry, prototype));
        }
    };

    public CarrierSnapshot project(CarrierSnapshot snapshot) {
        return project(snapshot, ComponentRegistry.empty(), ItemPrototype.empty());
    }

    public boolean matches(CarrierSnapshot a, CarrierSnapshot b) {
        return matches(a, b, ComponentRegistry.empty(), ItemPrototype.empty());
    }

    public abstract CarrierSnapshot project(CarrierSnapshot snapshot, ComponentRegistry registry, ItemPrototype prototype);

    public abstract boolean matches(CarrierSnapshot a, CarrierSnapshot b, ComponentRegistry registry, ItemPrototype prototype);
}
