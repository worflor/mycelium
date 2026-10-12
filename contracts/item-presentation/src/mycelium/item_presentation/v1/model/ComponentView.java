package mycelium.item_presentation.v1.model;

import java.util.Optional;

/**
 * What a stack's component state looks like to presentation: a consumer's adapter implements it over its carrier
 * snapshot, reading each field as the component's record defines it. Values are a {@code Boolean}, a whole number
 * ({@code Byte}, {@code Short}, {@code Integer} or {@code Long}) or a {@code String}; anything else reads as outside
 * the input's domain. A component at its default is read as its default, whether or not the carrier elides it.
 */
public interface ComponentView {
    /** Whether the unit component {@code componentId} is on the stack. */
    boolean has(String componentId);

    /** The value of the field at {@code fieldPath} in the component's record, or empty when there is none. */
    Optional<Object> field(String componentId, String fieldPath);
}
