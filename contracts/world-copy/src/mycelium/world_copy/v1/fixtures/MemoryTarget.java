package mycelium.world_copy.v1.fixtures;

import mycelium.world_copy.v1.model.BlockToken;
import mycelium.world_copy.v1.model.CopyTx;
import mycelium.world_copy.v1.model.Pos;
import mycelium.world_copy.v1.model.TargetPort;
import mycelium.world_copy.v1.model.Trait;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Reference in-memory target. Every mutation registers an undo. Unset positions read as {@link #VOID}. */
public class MemoryTarget implements TargetPort {
    public static final BlockToken VOID = new BlockToken("void");

    private final Map<Pos, BlockToken> tokens = new HashMap<>();
    private final Set<Pos> blockEntities = new HashSet<>();

    @Override
    public Set<Trait> traits() {
        return Set.of(Trait.ROLLBACK_PARTICIPATION, Trait.BLOCK_ENTITY_CONTROL);
    }

    @Override
    public void set(Pos pos, BlockToken token, boolean blockEntity, CopyTx tx) {
        BlockToken previous = tokens.put(pos, token);
        boolean previousEntity = blockEntities.contains(pos);
        if (blockEntity) {
            blockEntities.add(pos);
        } else {
            blockEntities.remove(pos);
        }
        tx.registerUndo(() -> {
            if (previous == null) {
                tokens.remove(pos);
            } else {
                tokens.put(pos, previous);
            }
            if (previousEntity) {
                blockEntities.add(pos);
            } else {
                blockEntities.remove(pos);
            }
        });
    }

    @Override
    public void clearBlockEntity(Pos pos, CopyTx tx) {
        if (blockEntities.remove(pos)) {
            tx.registerUndo(() -> blockEntities.add(pos));
        }
    }

    @Override
    public BlockToken token(Pos pos) {
        return tokens.getOrDefault(pos, VOID);
    }

    @Override
    public boolean hasBlockEntity(Pos pos) {
        return blockEntities.contains(pos);
    }

    /** Fixture setup outside any transaction. */
    public MemoryTarget seed(Pos pos, BlockToken token, boolean blockEntity) {
        tokens.put(pos, token);
        if (blockEntity) {
            blockEntities.add(pos);
        } else {
            blockEntities.remove(pos);
        }
        return this;
    }

    public int writtenPositions() {
        return tokens.size();
    }
}
