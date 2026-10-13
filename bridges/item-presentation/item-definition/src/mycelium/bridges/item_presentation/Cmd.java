package mycelium.bridges.item_presentation;

import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.Selector;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;

import java.util.ArrayList;
import java.util.List;

/**
 * Where this Minecraft version keeps the selector, and the only place the game's own types for it show through: custom model
 * data. This is the {@code item-definition} variant's (1.21.4 on): {@code minecraft:custom_model_data} is a record of floats,
 * flags, strings and colours, and the selector is its first string. Another mod's numbers, flags, colours and further strings
 * stay where they are.
 */
final class Cmd {
    private Cmd() {
    }

    /** What the plan must have been compiled for to be read by this version. */
    static final Realization REALIZATION = Realization.ITEM_DEFINITION;

    static Selector read(ItemStack stack) {
        CustomModelData data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        String key = data == null ? null : data.getString(0);
        return key == null || key.isEmpty() ? new Selector.None() : new Selector.Key(key);
    }

    static void write(ItemStack stack, Selector selector) {
        CustomModelData old = stack.get(DataComponents.CUSTOM_MODEL_DATA);
        List<String> strings = new ArrayList<>(old == null ? List.<String>of() : old.strings());
        if (selector instanceof Selector.Key key) {
            if (strings.isEmpty()) {
                strings.add(key.key());
            } else {
                strings.set(0, key.key());
            }
        } else if (strings.size() == 1) {
            strings.clear();
        } else if (!strings.isEmpty()) {
            strings.set(0, ""); // a later string is another mod's: the first becomes one no case names
        }
        List<Float> floats = old == null ? List.<Float>of() : old.floats();
        List<Boolean> flags = old == null ? List.<Boolean>of() : old.flags();
        List<Integer> colors = old == null ? List.<Integer>of() : old.colors();
        if (floats.isEmpty() && flags.isEmpty() && strings.isEmpty() && colors.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_MODEL_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(floats, flags, strings, colors));
        }
    }

    static void writeRaw(CompoundTag item, Selector selector) {
        Tag existing = item.get("components");
        CompoundTag components = existing instanceof CompoundTag c ? c : new CompoundTag();
        if (selector instanceof Selector.Key key) {
            CompoundTag data = new CompoundTag();
            ListTag strings = new ListTag();
            strings.add(StringTag.valueOf(key.key()));
            data.put("strings", strings);
            components.put("minecraft:custom_model_data", data);
            item.put("components", components);
        }
    }
}
