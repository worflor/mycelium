package mycelium.bridges.lifecycle;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The world's {@link Saved}, in the overworld's saved data (decision 0016, the Threshold's bridge): the stored form of every durable
 * slot of every player and of the server, in one file in the world folder beside the game's own, written by the game whenever it
 * saves and read when the world loads. It touches no player's file and no entity, and it is the same on every loader.
 *
 * <p>Before the game writes it, the lifecycles that hold it write their live hosts' durable values into it ({@link #beforeSave}), so what
 * is on disk is what is live at the save whichever order the loader's events and the game's saves came in, and it is saved on every
 * save while a lifecycle with durable slots holds it ({@link #alwaysSave}). This is the shape of Minecraft 26.3 on: a
 * {@code SavedDataType} with an {@code Identifier} and a codec.
 */
final class LifecycleData extends SavedData {
    private static final Codec<LifecycleData> CODEC = CompoundTag.CODEC.xmap(
            tag -> new LifecycleData(Saved.fromTag(Nbt.compound(tag))), LifecycleData::toCompound);
    private static final Map<String, SavedDataType<LifecycleData>> TYPES = new HashMap<>();

    private final Saved saved;
    private final List<Runnable> beforeSave = new ArrayList<>();
    private boolean always;

    LifecycleData(Saved saved) {
        this.saved = saved;
    }

    /** The world's data under {@code name} (a file of the world folder), made empty if the world has none yet. */
    static LifecycleData of(MinecraftServer server, String name) {
        SavedDataType<LifecycleData> type = TYPES.computeIfAbsent(name,
                n -> new SavedDataType<>(Identifier.fromNamespaceAndPath("mycelium", n), () -> new LifecycleData(new Saved()), CODEC, null));
        return Game.overworld(server).getDataStorage().computeIfAbsent(type);
    }

    /** What the game writes at a save and reads at the next load: the forms through the codec it registered, in the game's tag format. */
    static Saved roundTrip(MinecraftServer server, Saved saved) {
        Tag tag = CODEC.encodeStart(NbtOps.INSTANCE, new LifecycleData(saved.copy())).getOrThrow();
        return CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().saved;
    }

    Saved saved() {
        return saved;
    }

    /** A form was put or removed: the game saves the data at its next save. */
    void changed() {
        setDirty();
    }

    void beforeSave(Runnable flush) {
        if (!beforeSave.contains(flush)) {
            beforeSave.add(flush);
        }
    }

    void removeBeforeSave(Runnable flush) {
        beforeSave.remove(flush);
    }

    /** Whether the data is written at every save, not only after a change: it is while a lifecycle with durable slots holds it. */
    void alwaysSave(boolean on) {
        this.always = on;
    }

    @Override
    public boolean isDirty() {
        return always || super.isDirty();
    }

    private CompoundTag toCompound() {
        for (Runnable flush : new ArrayList<>(beforeSave)) {
            flush.run();
        }
        return Nbt.compoundTag(saved.toTag());
    }
}
