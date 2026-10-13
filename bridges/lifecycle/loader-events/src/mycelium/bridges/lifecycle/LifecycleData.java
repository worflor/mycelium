package mycelium.bridges.lifecycle;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.List;

/**
 * The world's {@link Saved}, in the overworld's saved data (decision 0016, the Threshold's bridge): the stored form of every durable
 * slot of every player and of the server, in one file in the world folder beside the game's own, written by the game whenever it
 * saves and read when the world loads. It touches no player's file and no entity, and it is the same on every loader.
 *
 * <p>Before the game writes it, the lifecycles that hold it write their live hosts' durable values into it ({@link #beforeSave}),
 * so what is on disk is what is live at the save whichever order the loader's events and the game's saves came in, and it is saved
 * on every save while a lifecycle with durable slots holds it ({@link #alwaysSave}). The saved-data plumbing is what changes with the
 * version (this is the shape of Minecraft 1.20.5 to 1.21.4: a {@code Factory} with a registry provider), so this file is what an overlay
 * replaces.
 */
final class LifecycleData extends SavedData {
    private final Saved saved;
    private final List<Runnable> beforeSave = new ArrayList<>();
    private boolean always;

    LifecycleData(Saved saved) {
        this.saved = saved;
    }

    /** The world's data under {@code name} (a file of the world folder), made empty if the world has none yet. */
    static LifecycleData of(MinecraftServer server, String name) {
        return Game.overworld(server).getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                () -> new LifecycleData(new Saved()),
                (tag, registries) -> new LifecycleData(Saved.fromTag(Nbt.compound(tag))), null), name);
    }

    /** What the game writes at a save and reads at the next load: the forms through the game's own tag format, read as the loader function reads them. */
    static Saved roundTrip(MinecraftServer server, Saved saved) {
        CompoundTag tag = new LifecycleData(saved.copy()).save(new CompoundTag(), server.registryAccess());
        return Saved.fromTag(Nbt.compound(tag));
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

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (Runnable flush : new ArrayList<>(beforeSave)) {
            flush.run();
        }
        CompoundTag out = Nbt.compoundTag(saved.toTag());
        for (String key : out.getAllKeys()) {
            tag.put(key, out.get(key));
        }
        return tag;
    }
}
