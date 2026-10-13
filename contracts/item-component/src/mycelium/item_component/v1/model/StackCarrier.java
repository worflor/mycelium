package mycelium.item_component.v1.model;

import java.util.Optional;

/**
 * The port a platform's item stack satisfies: {@link FencedCarrier}'s reads and writes, taken on a stack instead of on its
 * root compound. {@code S} is the platform's stack type, which this model never names; a bridge (Mycelium's
 * {@code item-carrier}) implements the port for Minecraft's {@code ItemStack}, and a mod that writes its own implements the
 * same interface, so the laws below apply to either.
 *
 * <p>An implementation does what a mod used to hand-write: it takes the stack's root compound out (on a modern stack the
 * {@code minecraft:custom_data} compound, on an older one the root tag), converts it to a {@link TagNode.TagCompound},
 * lets {@link #carrier()} do everything else, and puts the result back. The stack laws (S1 to S6, SPEC section 5.7) are
 * the ones {@link FencedCarrier}'s laws do not reach: what a real stack does with the result.
 *
 * <p>An empty stack (nothing, or the platform's air) carries nothing: reads of it are empty, and a write to it is
 * {@code Written(root, false)} that changes nothing.
 *
 * @param <S> the platform's item stack
 */
public interface StackCarrier<S> {
    /** The pure carrier this port applies to the stack's root: its registry and its migration are the ones in force. */
    FencedCarrier carrier();

    /** Everything the stack carries, old keys migrated in (T16). Never throws on stored data. */
    CarrierSnapshot read(S stack);

    /** One component of the stack, by the same decode path as {@link #read(Object)} (T15). */
    <T> ComponentRead<T> read(S stack, ComponentDefinition<T> def);

    /**
     * Stores a snapshot on the stack. A refusal changes nothing on the stack (S4); a write that changes nothing leaves the
     * stack as it was.
     */
    WriteOutcome write(S stack, CarrierSnapshot snapshot, OnQuarantine mode);

    /** Puts one component on the stack, keeping everything else it carries. */
    <T> WriteOutcome set(S stack, ComponentDefinition<T> def, T value, OnQuarantine mode);

    /** Takes one component off the stack; with nothing else left to carry, the stack is again as a plain one (S2). */
    WriteOutcome clear(S stack, ComponentDefinition<?> def, OnQuarantine mode);

    default WriteOutcome write(S stack, CarrierSnapshot snapshot) {
        return write(stack, snapshot, OnQuarantine.REFUSE);
    }

    default <T> WriteOutcome set(S stack, ComponentDefinition<T> def, T value) {
        return set(stack, def, value, OnQuarantine.REFUSE);
    }

    default WriteOutcome clear(S stack, ComponentDefinition<?> def) {
        return clear(stack, def, OnQuarantine.REFUSE);
    }

    /** The component's value when the stack carries it. */
    default <T> Optional<T> get(S stack, ComponentDefinition<T> def) {
        return read(stack, def).value();
    }

    /** Whether the stack carries the component (for a presence component, whether the mark is there). */
    default boolean has(S stack, ComponentDefinition<?> def) {
        return read(stack, def).isPresent();
    }
}
