package mycelium.bridges.block_catalog.conformance;

import mycelium.block_catalog.v1.model.BlockCatalog;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent native observations for the port cases; does not reference a bridge implementation. */
public final class CatalogWire {
    private CatalogWire() { }

    public static String version() { return SharedConstants.getCurrentVersion().id(); }

    public static Map<String, BlockCatalog.Entry> entries() {
        Map<String, BlockCatalog.Entry> result = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Map<String, List<String>> domains = new LinkedHashMap<>();
            Map<String, String> defaults = new LinkedHashMap<>();
            for (Property<?> property : block.getStateDefinition().getProperties()) {
                List<String> values = new ArrayList<>();
                for (Object value : property.getPossibleValues()) values.add(name(property, value));
                domains.put(property.getName(), List.copyOf(values));
                defaults.put(property.getName(), defaultName(block.defaultBlockState(), property));
            }
            result.put(BuiltInRegistries.BLOCK.getKey(block).toString(), new BlockCatalog.Entry(domains, defaults));
        }
        return Map.copyOf(result);
    }

    private static <T extends Comparable<T>> String name(Property<T> property, Object value) {
        return property.getName(property.getValueClass().cast(value));
    }

    private static <T extends Comparable<T>> String defaultName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
