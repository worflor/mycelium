package mycelium.bridges.world_clock;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

import java.util.function.Supplier;

/**
 * A mod's clocks in the overworld's saved data (decision 0018): one string, {@link ClockText}, in the world folder beside
 * the game's own, saved by the game when it saves and never touching a player or an entity. This is the shape of Minecraft 1.21.5 on: a {@code SavedDataType} with a codec.
 */
final class ClockStore extends SavedData implements WorldClocks.Storage {
    private static final Codec<ClockStore> CODEC = Codec.STRING.fieldOf("clocks").codec().xmap(ClockStore::new, ClockStore::textNow);
    private static final Map<String, SavedDataType<ClockStore>> TYPES = new HashMap<>();

    private String text;
    private Supplier<String> live;

    private ClockStore(String text) {
        this.text = text;
    }

    /** The store under {@code name} in the overworld's saved data, made empty if the world has none yet. */
    static WorldClocks.Storage of(MinecraftServer server, String name) {
        SavedDataType<ClockStore> type = TYPES.computeIfAbsent(name, n -> new SavedDataType<>(n, () -> new ClockStore(""), CODEC, null));
        return Game.overworld(server).getDataStorage().computeIfAbsent(type);
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
        // what the game writes at a save and reads at the next start: the codec it registered, through the game's tag format
        Tag tag = CODEC.encodeStart(NbtOps.INSTANCE, this).getOrThrow();
        return WorldClocks.Storage.memory(CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow().text);
    }
}
