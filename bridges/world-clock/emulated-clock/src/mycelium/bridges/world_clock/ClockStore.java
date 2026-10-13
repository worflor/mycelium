package mycelium.bridges.world_clock;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.function.Supplier;

/**
 * A mod's clocks in the overworld's saved data (decision 0018): one string, {@link ClockText}, in the world folder beside
 * the game's own, saved by the game when it saves and never touching a player or an entity. The saved-data plumbing is what
 * changes with the version (this is the shape of Minecraft 1.20.5 to 1.21.4: a {@code Factory} with a registry provider), so
 * this file is what an overlay replaces.
 */
final class ClockStore extends SavedData implements WorldClocks.Storage {
    private static final String KEY = "clocks";

    private final MinecraftServer server;
    private String text;
    private Supplier<String> live;

    private ClockStore(MinecraftServer server, String text) {
        this.server = server;
        this.text = text;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(KEY, live != null ? live.get() : text);
        return tag;
    }

    /** The store under {@code name} in the overworld's saved data, made empty if the world has none yet. */
    static WorldClocks.Storage of(MinecraftServer server, String name) {
        return Game.overworld(server).getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                () -> new ClockStore(server, ""), (tag, registries) -> new ClockStore(server, tag.getString(KEY)), null), name);
    }

    @Override
    public String load() {
        return text;
    }

    @Override
    public void attach(Supplier<String> source) {
        this.live = source;
    }

    @Override
    public void changed() {
        setDirty();
    }

    @Override
    public WorldClocks.Storage reloaded() {
        // what the game writes at a save and reads at the next start: the tag it saves, read by the loader it registered
        CompoundTag tag = save(new CompoundTag(), server.registryAccess());
        return WorldClocks.Storage.memory(new ClockStore(server, tag.getString(KEY)).text);
    }
}
