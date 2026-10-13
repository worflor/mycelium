package mycelium.bridges.item_carrier.conformance;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * What the conformance cases need of a real stack that differs by variant (decision 0016): copy, compare, save and reload,
 * send to a client, and reach the raw tag the carrier keeps its state in. This is the {@code root-tag} variant's
 * (Minecraft 1.14 to 1.20.4): the state is the stack's root tag, a stack is saved with {@code ItemStack.save} and loaded with
 * {@code ItemStack.of}, and sent through {@code FriendlyByteBuf.writeItem}.
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

    /**
     * Whether the two stack together. The game compares the item and the tags, and a null tag and an empty tag are not the
     * same (they do not stack), which is what {@code ItemStack.matches} sees on two stacks of one.
     */
    static boolean same(ItemStack a, ItemStack b) {
        return a.getCount() == b.getCount() && ItemStack.matches(a, b);
    }

    /** Whether the two are equal stacks, count included. */
    static boolean equal(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    /** Whether the stack holds no state of its own: a null tag (not an empty one). */
    static boolean carriesNothing(ItemStack stack) {
        return stack.getTag() == null;
    }

    private static void put(ItemStack stack, String key, Tag value) {
        stack.getOrCreateTag().put(key, value);
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
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag fence = new CompoundTag();
        Tag existing = root.get("mycelium$c");
        if (existing instanceof CompoundTag c) {
            fence = c;
        }
        fence.put(key, StringTag.valueOf(value));
        root.put("mycelium$c", fence);
    }

    static boolean has(ItemStack stack, String key) {
        CompoundTag root = stack.getTag();
        return root != null && root.get(key) != null;
    }

    /** The string at {@code key} of the stack's root, or null. */
    static String text(ItemStack stack, String key) {
        CompoundTag root = stack.getTag();
        Tag t = root == null ? null : root.get(key);
        return t == null ? null : t.getAsString();
    }

    /** The stack's raw root as text, to compare and to look into. */
    static String rootText(ItemStack stack) {
        CompoundTag root = stack.getTag();
        return root == null ? "" : root.toString();
    }

    /** The stack saved the way the game saves one and loaded again. */
    static ItemStack saveAndReload(Level level, ItemStack stack) {
        return ItemStack.of(stack.save(new CompoundTag()));
    }

    /** The stack as a client receives it: written to a packet buffer and read back. */
    static ItemStack sync(Level level, ItemStack stack) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeItem(stack);
        return buffer.readItem();
    }
}
