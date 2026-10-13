package mycelium.bridges.item_presentation;

import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selector;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

/**
 * Where this Minecraft version keeps the selector, and the only place the game's own types for it show through: custom model
 * data. This is the {@code model-overrides} variant's 1.20.5 overlay (1.20.5 to 1.21.3): the stack's state became components,
 * and {@code minecraft:custom_model_data} is a record of one number. A stack with no selector has no such component.
 */
final class Cmd {
    private Cmd() {
    }

    /** What the plan must have been compiled for to be read by this version. */
    static final Realization REALIZATION = Realization.MODEL_OVERRIDES;

    private static final String KEY = "minecraft:custom_model_data";

    static Selector read(ItemStack stack) {
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        return data == null || data.value() == 0 ? new Selector.None() : new Selector.Code(data.value());
    }

    static void write(ItemStack stack, Selector selector) {
        if (selector instanceof Selector.Code code) {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(code.code()));
        } else {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        }
    }

    static void writeRaw(CompoundTag item, Selector selector) {
        Tag existing = item.get("components");
        CompoundTag components = existing instanceof CompoundTag c ? c : new CompoundTag();
        if (selector instanceof Selector.Code code) {
            components.putInt(KEY, code.code());
            item.put("components", components);
        }
    }
}
