package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * How the harness turns a {@link RecordCodec} into a registered component. The reference binds it as the model does;
 * a broken binding wraps the codec and gets one thing wrong.
 */
public interface RecordBinding {
    <T> ComponentDefinition<T> bind(ComponentId id, RecordCodec<T> codec, T defaultValue);

    /** The model's own binding. */
    static RecordBinding reference() {
        return new RecordBinding() {
            @Override
            public <T> ComponentDefinition<T> bind(ComponentId id, RecordCodec<T> codec, T defaultValue) {
                return codec.definition(id, defaultValue);
            }
        };
    }
}
