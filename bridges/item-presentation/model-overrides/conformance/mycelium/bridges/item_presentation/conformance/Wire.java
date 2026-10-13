package mycelium.bridges.item_presentation.conformance;

import io.netty.buffer.Unpooled;
import mycelium.bridges.item_presentation.ItemPresenter;
import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.StackPresenter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the conformance cases need of a real stack that differs by variant (decision 0016): compare, save and reload, send to a
 * client, the item data a display entity is built from, and the stack's custom model data read from outside the presenter. This
 * is the {@code model-overrides} variant's base form (Minecraft 1.14 to 1.20.4): the selector is the number at
 * {@code CustomModelData} in the stack's root tag, a stack is saved with {@code ItemStack.save} and loaded with
 * {@code ItemStack.of}, and sent through {@code FriendlyByteBuf.writeItem}; the witness reads the saved form's text, so it shares
 * nothing with the presenter but the game.
 */
final class Wire {
    private Wire() {
    }

    /** For a refusal's words only. */
    static final String VERSION = "1.14 to 1.20.4";

    /** What the cases compile their program for. */
    static final Realization REALIZATION = Realization.MODEL_OVERRIDES;

    private static final Pattern NUMBER = Pattern.compile("CustomModelData\\s*:\\s*(\\d+)");

    static ItemStack plain() {
        return new ItemStack(Items.STONE);
    }

    static ItemStack copy(ItemStack stack) {
        return stack.copy();
    }

    /** Whether the two stack together: the game compares the item and the tags (a null tag and an empty one do not stack). */
    static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.matches(a, b);
    }

    /** Whether the two are equal stacks, count included. */
    static boolean equal(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    /** Whether the stack has no tag at all (not an empty one), so it is again a plain stack. */
    static boolean carriesNoModelData(ItemStack stack) {
        return stack.getTag() == null;
    }

    /** Another mod's data, beside the number: a key of the root tag, which a selector must leave alone. */
    static void addNeighbour(ItemStack stack) {
        stack.getOrCreateTag().putInt("othermod", 1);
    }

    static boolean hasNeighbour(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains("othermod");
    }

    /** The item as the game saves it: id, count and tag, the form a display entity's {@code item} takes. */
    static CompoundTag itemData(Level level, ItemStack stack) {
        return stack.save(new CompoundTag());
    }

    static ItemStack fromItemData(Level level, CompoundTag data) {
        return ItemStack.of(data);
    }

    /** The selector the game's own saved form of the stack holds, read from its text; nothing of the presenter is involved. */
    static Selector rawSelector(Level level, ItemStack stack) {
        Matcher m = NUMBER.matcher(itemData(level, stack).toString());
        return m.find() && Integer.parseInt(m.group(1)) != 0 ? new Selector.Code(Integer.parseInt(m.group(1))) : new Selector.None();
    }

    static ItemStack saveAndReload(Level level, ItemStack stack) {
        return fromItemData(level, itemData(level, stack));
    }

    /** The stack as a client receives it: written to a packet buffer and read back. */
    static ItemStack sync(Level level, ItemStack stack) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeItem(stack);
        return buffer.readItem();
    }

    /** Whether the presenter is the bridge's, which writes raw item data. */
    static boolean writesRaw(StackPresenter<ItemStack> presenter) {
        return presenter instanceof ItemPresenter;
    }

    static void writeRaw(StackPresenter<ItemStack> presenter, CompoundTag item, CarrierSnapshot state) {
        ((ItemPresenter) presenter).writeRaw(item, state);
    }
}
