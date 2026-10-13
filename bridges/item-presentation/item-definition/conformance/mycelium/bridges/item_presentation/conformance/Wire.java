package mycelium.bridges.item_presentation.conformance;

import io.netty.buffer.Unpooled;
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
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the conformance cases need of a real stack that differs by variant (decision 0016): compare, save and reload, send to a
 * client, the item data a display entity is built from, and the stack's custom model data read from outside the presenter. This
 * is the {@code item-definition} variant's (Minecraft 1.21.4 on): the selector is the first custom model data string, a stack
 * is saved through {@code ItemStack.CODEC} and sent through {@code ItemStack.STREAM_CODEC}, and the witness reads the saved form's
 * text, so it shares nothing with the presenter but the game.
 */
final class Wire {
    private Wire() {
    }

    /** For a refusal's words only. */
    static final String VERSION = "1.21.4 on";

    /** What the cases compile their program for. */
    static final Realization REALIZATION = Realization.ITEM_DEFINITION;

    private static final Pattern STRING = Pattern.compile("custom_model_data\"?\\s*:\\s*\\{[^}]*?strings\\s*:\\s*\\[\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    /** Another mod's custom model data: a number, a flag and a colour, which a selector must leave alone. */
    static void addNeighbour(ItemStack stack) {
        stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(1.5F), List.of(true), List.of(), List.of(0x112233)));
    }

    static boolean hasNeighbour(ItemStack stack) {
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return data != null && data.floats().equals(List.of(1.5F)) && data.flags().equals(List.of(true)) && data.colors().equals(List.of(0x112233));
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
        Matcher m = STRING.matcher(itemData(level, stack).toString());
        return m.find() && !m.group(1).isEmpty() ? new Selector.Key(m.group(1)) : new Selector.None();
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

    /**
     * Whether the presenter writes raw item data: the bridge's does, with a public {@code writeRaw(CompoundTag, CarrierSnapshot)}, and a
     * mod's own presenter that offers the same method is held to the same cases. Asked of the method and not of the bridge's class,
     * so these cases need none of the bridge's source (a mod that overrides the bridge imports them).
     */
    static boolean writesRaw(StackPresenter<ItemStack> presenter) {
        return rawWriter(presenter) != null;
    }

    static void writeRaw(StackPresenter<ItemStack> presenter, CompoundTag item, CarrierSnapshot state) {
        try {
            rawWriter(presenter).invoke(presenter, item, state);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException failure) {
                throw failure;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new AssertionError("item-presentation conformance: the presenter's writeRaw failed: " + cause, cause);
        } catch (IllegalAccessException e) {
            throw new AssertionError("item-presentation conformance: the presenter's writeRaw is not reachable: " + e, e);
        }
    }

    private static java.lang.reflect.Method rawWriter(StackPresenter<ItemStack> presenter) {
        try {
            return presenter.getClass().getMethod("writeRaw", CompoundTag.class, CarrierSnapshot.class);
        } catch (NoSuchMethodException none) {
            return null;
        }
    }
}
