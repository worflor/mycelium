package mycelium.bridges.item_presentation.conformance;

import io.netty.buffer.Unpooled;
import mycelium.bridges.item_presentation.ItemPresenter;
import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.StackPresenter;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the conformance cases need of a real stack that differs by variant (decision 0016): compare, save and reload, send to a
 * client, the item data a display entity is built from, and the stack's custom model data read from outside the presenter. This
 * is the {@code model-overrides} variant's 1.20.5 overlay (1.20.5 to 1.21.3): the selector is the number of the
 * {@code minecraft:custom_model_data} component, a stack is saved through {@code ItemStack.CODEC} and sent through
 * {@code ItemStack.STREAM_CODEC}, and the witness reads the saved form's text, so it shares nothing with the presenter but the game.
 */
final class Wire {
    private Wire() {
    }

    /** For a refusal's words only. */
    static final String VERSION = "1.20.5 to 1.21.3";

    /** What the cases compile their program for. */
    static final Realization REALIZATION = Realization.MODEL_OVERRIDES;

    private static final Pattern NUMBER = Pattern.compile("custom_model_data\"?\\s*:\\s*(\\d+)");

    static ItemStack plain() {
        return new ItemStack(Items.STONE);
    }

    static ItemStack copy(ItemStack stack) {
        return stack.copy();
    }

    /** Whether the two stack together: the same item with the same components (what stacking compares). */
    static boolean same(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /** Whether the two are equal stacks, count included. */
    static boolean equal(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    /** Whether the stack has no custom model data at all. */
    static boolean carriesNoModelData(ItemStack stack) {
        return stack.get(DataComponents.CUSTOM_MODEL_DATA) == null;
    }

    /** Another mod's data, beside the number: custom data, which a selector must leave alone. */
    static void addNeighbour(ItemStack stack) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("othermod", 1);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    static boolean hasNeighbour(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.copyTag().contains("othermod");
    }

    private static RegistryOps<Tag> ops(Level level) {
        return RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
    }

    /** The item as the game saves it: id, count and components, the form a display entity's {@code item} takes. */
    static CompoundTag itemData(Level level, ItemStack stack) {
        return (CompoundTag) ItemStack.CODEC.encodeStart(ops(level), stack).result().orElseThrow();
    }

    static ItemStack fromItemData(Level level, CompoundTag data) {
        return ItemStack.CODEC.parse(ops(level), data).result().orElseThrow();
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
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ItemStack.STREAM_CODEC.encode(buffer, stack);
        return ItemStack.STREAM_CODEC.decode(buffer);
    }

    /** Whether the presenter is the bridge's, which writes raw item data. */
    static boolean writesRaw(StackPresenter<ItemStack> presenter) {
        return presenter instanceof ItemPresenter;
    }

    static void writeRaw(StackPresenter<ItemStack> presenter, CompoundTag item, CarrierSnapshot state) {
        ((ItemPresenter) presenter).writeRaw(item, state);
    }
}
