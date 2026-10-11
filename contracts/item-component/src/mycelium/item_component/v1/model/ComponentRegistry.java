package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Registry of known {@link ComponentDefinition} specifications for an era or runtime.
 */
public final class ComponentRegistry {
    private final Map<ComponentId, ComponentDefinition<?>> definitions;

    private ComponentRegistry(Map<ComponentId, ComponentDefinition<?>> definitions) {
        this.definitions = Collections.unmodifiableMap(new TreeMap<>(definitions));
    }

    public static ComponentRegistry empty() {
        return new ComponentRegistry(Collections.emptyMap());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ComponentDefinition<?>> get(ComponentId id) {
        return Optional.ofNullable(definitions.get(id));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<ComponentDefinition<T>> getTyped(ComponentId id) {
        return Optional.ofNullable((ComponentDefinition<T>) definitions.get(id));
    }

    public boolean has(ComponentId id) {
        return definitions.containsKey(id);
    }

    public Collection<ComponentDefinition<?>> definitions() {
        return definitions.values();
    }

    public static final class Builder {
        private final Map<ComponentId, ComponentDefinition<?>> defs = new HashMap<>();

        public <T> Builder register(ComponentDefinition<T> def) {
            Objects.requireNonNull(def, "def");
            defs.put(def.id(), def);
            return this;
        }

        public <T> Builder register(ComponentId id, ComponentCodec<T> codec, T defaultValue) {
            return register(new ComponentDefinition<>(id, codec, defaultValue));
        }

        public ComponentRegistry build() {
            return new ComponentRegistry(defs);
        }
    }
}
