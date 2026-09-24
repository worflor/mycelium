package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.Pos;
import mycelium.world_copy.v1.model.Region;
import mycelium.world_copy.v1.model.SourceView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** In-memory source dimension. Positions never set read as the fallback token with no block entity. */
public final class MemorySource implements SourceView {
    private final Map<Pos, BlockToken> tokens = new HashMap<>();
    private final Set<Pos> blockEntities = new HashSet<>();
    private final BlockToken fallback;

    public MemorySource(BlockToken fallback) {
        this.fallback = fallback;
    }

    public MemorySource put(Pos pos, BlockToken token, boolean blockEntity) {
        tokens.put(pos, token);
        if (blockEntity) {
            blockEntities.add(pos);
        } else {
            blockEntities.remove(pos);
        }
        return this;
    }

    public MemorySource fill(Region region, BlockToken token) {
        for (Pos pos : region.positions()) {
            put(pos, token, false);
        }
        return this;
    }

    @Override
    public BlockToken token(Pos pos) {
        return tokens.getOrDefault(pos, fallback);
    }

    @Override
    public boolean hasBlockEntity(Pos pos) {
        return blockEntities.contains(pos);
    }
}
