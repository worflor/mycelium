package mycelium.bridges.item_carrier.conformance;

import io.netty.buffer.Unpooled;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * What the conformance cases need of a real stack that differs by variant (decision 0016): copy, compare, save and reload,
 * send to a client, and reach the raw tag the carrier keeps its state in. This is the {@code custom-data} variant's
 * (Minecraft 1.20.5 on): the state is {@code minecraft:custom_data}, a stack is saved through {@code ItemStack.CODEC} and sent
 * through {@code ItemStack.STREAM_CODEC}.
 */
final class Wire {
    private Wire() {
    }

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

    /** Whether the stack holds no state of its own: no {@code custom_data} at all (not an empty one). */
    static boolean carriesNothing(ItemStack stack) {
        return stack.get(DataComponents.CUSTOM_DATA) == null;
    }

    private static CompoundTag root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : data.copyTag();
    }

    private static void put(ItemStack stack, String key, Tag value) {
        CompoundTag root = root(stack);
        root.put(key, value);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    /** Another mod's string at the root of the stack's tag. */
    static void putText(ItemStack stack, String key, String value) {
        put(stack, key, StringTag.valueOf(value));
    }

    static void putInt(ItemStack stack, String key, int value) {
        put(stack, key, IntTag.valueOf(value));
    }

    /** A string at {@code key} inside the fenced compound, where a component's entry lives. */
    static void putFencedText(ItemStack stack, String key, String value) {
        CompoundTag root = root(stack);
        CompoundTag fence = new CompoundTag();
        Tag existing = root.get("mycelium$c");
        if (existing instanceof CompoundTag c) {
            fence = c;
        }
        fence.put(key, StringTag.valueOf(value));
        root.put("mycelium$c", fence);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
    }

    static boolean has(ItemStack stack, String key) {
        return root(stack).get(key) != null;
    }

    /** The string at {@code key} of the stack's root, or null. */
    static String text(ItemStack stack, String key) {
        Tag t = root(stack).get(key);
        return t == null ? null : t.getAsString();
    }

    /** The stack's raw root as text, to compare and to look into. */
    static String rootText(ItemStack stack) {
        return root(stack).toString();
    }

    /** The stack saved through the game's own stack codec and loaded again. */
    static ItemStack saveAndReload(Level level, ItemStack stack) {
        RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, level.registryAccess());
        Tag saved = ItemStack.CODEC.encodeStart(ops, stack).result().orElseThrow();
        return ItemStack.CODEC.parse(ops, saved).result().orElseThrow();
    }

    /** The stack as a client receives it: written to a packet buffer and read back. */
    static ItemStack sync(Level level, ItemStack stack) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        ItemStack.STREAM_CODEC.encode(buffer, stack);
        return ItemStack.STREAM_CODEC.decode(buffer);
    }
}
