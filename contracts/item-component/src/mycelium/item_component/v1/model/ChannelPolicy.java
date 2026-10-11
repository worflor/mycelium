package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Channel projection policy for specializing item snapshots across operational boundaries:
 * <ul>
 *     <li>{@link #PERSISTENT}: Full disk persistence (retains all foreign/unmapped/quarantined tags).</li>
 *     <li>{@link #NETWORK}: Client-bound networking (filters server-internal diagnostics and transient tags).</li>
 *     <li>{@link #EFFECTIVE}: Live gameplay interaction state.</li>
 *     <li>{@link #IDENTITY}: Stacking equivalence: items match if and only if their identifying state is equal.</li>
 * </ul>
 */
public enum ChannelPolicy {
    PERSISTENT {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot) {
            return Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b) {
            return Objects.equals(a, b);
        }
    },

    NETWORK {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            Map<String, TagNode> filteredUnmapped = new TreeMap<>();
            for (Map.Entry<String, TagNode> e : snapshot.unmappedTags().entrySet()) {
                if (!e.getKey().startsWith("server_internal:") && !e.getKey().startsWith("!")) {
                    filteredUnmapped.put(e.getKey(), e.getValue());
                }
            }
            return new CarrierSnapshot(snapshot.components(), filteredUnmapped, snapshot.removals());
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b) {
            return Objects.equals(project(a), project(b));
        }
    },

    EFFECTIVE {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot) {
            return Objects.requireNonNull(snapshot, "snapshot");
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b) {
            return Objects.equals(a.components(), b.components());
        }
    },

    IDENTITY {
        @Override
        public CarrierSnapshot project(CarrierSnapshot snapshot) {
            Objects.requireNonNull(snapshot, "snapshot");
            Map<String, TagNode> cleanUnmapped = new TreeMap<>();
            for (Map.Entry<String, TagNode> e : snapshot.unmappedTags().entrySet()) {
                if (!e.getKey().startsWith("server_internal:") && !e.getKey().startsWith("!")) {
                    cleanUnmapped.put(e.getKey(), e.getValue());
                }
            }
            return new CarrierSnapshot(snapshot.components(), cleanUnmapped, snapshot.removals());
        }

        @Override
        public boolean matches(CarrierSnapshot a, CarrierSnapshot b) {
            return Objects.equals(project(a), project(b));
        }
    };

    public abstract CarrierSnapshot project(CarrierSnapshot snapshot);

    public abstract boolean matches(CarrierSnapshot a, CarrierSnapshot b);
}
