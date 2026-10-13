package mycelium.bridges.world_clock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.function.Supplier;

/**
 * A mod's clocks in the overworld's saved data (decision 0018): one string, {@link ClockText}, in the world folder beside
 * the game's own, saved by the game when it saves and never touching a player or an entity. This is the shape of Minecraft
 * 1.14 to 1.16: a saved data is made with its name, filled by {@code load(CompoundTag)} and saved by {@code save(CompoundTag)}.
 */
final class ClockStore extends SavedData implements WorldClocks.Storage {
    private static final String KEY = "clocks";

    private final String name;
    private String text;
    private Supplier<String> live;

    private ClockStore(String name, String text) {
        super(name);
        this.name = name;
        this.text = text;
    }

    @Override
    public void load(CompoundTag tag) {
        this.text = tag.getString(KEY);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString(KEY, textNow());
        return tag;
    }

    /** The store under {@code name} in the overworld's saved data, made empty if the world has none yet. */
    static WorldClocks.Storage of(MinecraftServer server, String name) {
        return Game.overworld(server).getDataStorage().computeIfAbsent(() -> new ClockStore(name, ""), name);
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

    private String textNow() {
        return live != null ? live.get() : text;
    }

    @Override
    public WorldClocks.Storage reloaded() {
        // what the game writes at a save and reads at the next start: the tag it saves, read by the loader it registered
        CompoundTag tag = save(new CompoundTag());
        ClockStore back = new ClockStore(name, "");
        back.load(tag);
        return WorldClocks.Storage.memory(back.text);
    }
}
