package mycelium.bridges.block_catalog;

import mycelium.block_catalog.v2.model.BlockCatalog;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native property serialization is the game's own Property vocabulary, never enum.toString(). */
final class Ports {
    private Ports() { }

    static BlockCatalog capture() {
        Map<String, BlockCatalog.Entry> entries = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Map<String, List<String>> properties = new TreeMap<>();
            Map<String, String> defaults = new TreeMap<>();
            for (Property<?> property : block.getStateDefinition().getProperties()) {
                // getPossibleValues() is the game's declared order: a direction enum's own order,
                // and a numeric property ascending. block.catalog@2 C6 keeps it, so do not sort.
                properties.put(property.getName(), property.getPossibleValues().stream()
                        .map(value -> valueName(property, value)).toList());
                defaults.put(property.getName(), defaultName(block.defaultBlockState(), property));
            }
            entries.put(BuiltInRegistries.BLOCK.getKey(block).toString(), new BlockCatalog.Entry(properties, defaults));
        }
        return new BlockCatalog(SharedConstants.getCurrentVersion().id(), entries);
    }

    private static <T extends Comparable<T>> String valueName(Property<T> property, Object value) {
        return property.getName(property.getValueClass().cast(value));
    }

    private static <T extends Comparable<T>> String defaultName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
