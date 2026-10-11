package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure model of an item's archetype or prototype state in an environment.
 * Distinguishes the item's baseline defaults (e.g. max damage, attributes)
 * from per-stack patch overrides and removals.
 */
public interface ItemPrototype {
    ItemPrototype EMPTY = new ItemPrototype() {
        @Override
        public <T> Optional<T> getBaseComponent(ComponentId id) {
            return Optional.empty();
        }

        @Override
        public Map<ComponentId, Object> baseComponents() {
            return Collections.emptyMap();
        }

        @Override
        public boolean isEmpty() {
            return true;
        }
    };

    static ItemPrototype empty() {
        return EMPTY;
    }

    static Builder builder() {
        return new Builder();
    }

    <T> Optional<T> getBaseComponent(ComponentId id);

    @SuppressWarnings("unchecked")
    default <T> Optional<T> getBaseComponent(ComponentDefinition<T> def) {
        return getBaseComponent(def.id());
    }

    Map<ComponentId, Object> baseComponents();

    default boolean isEmpty() {
        return baseComponents().isEmpty();
    }

    final class Builder {
        private final Map<ComponentId, Object> base = new TreeMap<>();

        public Builder withBaseComponent(ComponentId id, Object value) {
            base.put(Objects.requireNonNull(id, "id"),
                    ValueSemantics.freeze(Objects.requireNonNull(value, "value")));
            return this;
        }

        public <T> Builder withBaseComponent(ComponentDefinition<T> def, T value) {
            return withBaseComponent(def.id(), value);
        }

        public ItemPrototype build() {
            final Map<ComponentId, Object> frozen = Collections.unmodifiableMap(new TreeMap<>(base));
            return new ItemPrototype() {
                @Override
                @SuppressWarnings("unchecked")
                public <T> Optional<T> getBaseComponent(ComponentId id) {
                    Object val = frozen.get(Objects.requireNonNull(id, "id"));
                    return val != null ? Optional.of((T) ValueSemantics.deepClone(val)) : Optional.empty();
                }

                @Override
                public Map<ComponentId, Object> baseComponents() {
                    return ValueSemantics.defensiveMapView(frozen);
                }

                @Override
                public boolean isEmpty() {
                    return frozen.isEmpty();
                }
            };
        }
    }
}
