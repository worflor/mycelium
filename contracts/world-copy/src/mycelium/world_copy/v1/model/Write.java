package mycelium.world_copy.v1.model;

import java.util.Objects;

public record Write(Pos pos, BlockToken token, boolean blockEntity) {
    public Write {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(token, "token");
    }
}
