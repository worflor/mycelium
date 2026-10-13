package mycelium.item_presentation.v1.model;

import mycelium.item_component.v1.model.CarrierSnapshot;

/**
 * The port of {@code item.presentation@1}: what the game's half does with the plan a compilation handed it. A bridge
 * ({@code bridges/item-presentation}) implements it for {@code ItemStack}: it asks the plan which model a stack's
 * components draw, and writes the selector the era's own machinery reads (custom model data) onto the stack.
 *
 * <p>The laws a presenter keeps (conformance cases in the bridge run them on real stacks):
 * <ul>
 *   <li><b>Round trip.</b> What {@link #apply} writes is what {@link #read} reads back, and is what the plan's
 *       {@link Plan#selectorOf} said: the key on the first era, the locked code on the second, nothing for the base model.</li>
 *   <li><b>Clearing.</b> Applying the base model leaves no selector, and a stack that carried nothing else is again
 *       identical to a plain one (it stacks with it).</li>
 *   <li><b>Neighbours.</b> A selector is the only custom model data it touches: another mod's numbers, flags and colours stay.</li>
 *   <li><b>Agreement.</b> {@link #present} draws what the plan selects for the stack's own components, as the carrier reads them.</li>
 * </ul>
 */
public interface StackPresenter<S> {
    /** The plan this presenter writes selectors for. */
    Plan plan();

    /** The selection these components draw (for a stack not made yet: a display body built from raw item data). */
    Selection select(CarrierSnapshot snapshot);

    /** The selection this stack's own components draw, as its carrier reads them. */
    Selection select(S stack);

    /** Writes the selector of {@code selection} onto the stack, replacing the one it carried; returns what it wrote. */
    Selector apply(S stack, Selection selection);

    /** The selector the stack carries now: {@link Selector.None} when it carries none. */
    Selector read(S stack);

    /** Selects for the stack's own components and writes it. */
    default Selector present(S stack) {
        return apply(stack, select(stack));
    }
}
