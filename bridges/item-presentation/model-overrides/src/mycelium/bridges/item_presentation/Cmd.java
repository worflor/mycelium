package mycelium.bridges.item_presentation;

import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * Where this Minecraft version keeps the selector, and the only place the game's own types for it show through: custom model
 * data. This is the {@code model-overrides} variant's base form (1.14 to 1.20.4): a number at {@code CustomModelData} in the
 * stack's root tag. A stack with nothing left in its tag has none at all, so it is again identical to a plain one (a null and
 * an empty tag do not stack).
 */
final class Cmd {
    private Cmd() {
    }

    /** What the plan must have been compiled for to be read by this version. */
    static final Realization REALIZATION = Realization.MODEL_OVERRIDES;

    private static final String KEY = "CustomModelData";

    static Selector read(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        int code = tag != null && tag.contains(KEY, 99) ? tag.getInt(KEY) : 0;
        return code == 0 ? new Selector.None() : new Selector.Code(code);
    }

    static void write(ItemStack stack, Selector selector) {
        if (selector instanceof Selector.Code code) {
            stack.getOrCreateTag().putInt(KEY, code.code());
            return;
        }
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove(KEY);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
    }

    static void writeRaw(CompoundTag item, Selector selector) {
        Tag existing = item.get("tag");
        CompoundTag tag = existing instanceof CompoundTag c ? c : new CompoundTag();
        if (selector instanceof Selector.Code code) {
            tag.putInt(KEY, code.code());
            item.put("tag", tag);
        }
    }
}
