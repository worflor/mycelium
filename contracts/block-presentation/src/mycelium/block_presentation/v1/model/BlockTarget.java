package mycelium.block_presentation.v1.model;

import java.util.Objects;

/** What a mosaic is compiled for: the Minecraft version, named only so a refusal can say it, and the realization the era table gives it. */
public record BlockTarget(String minecraft, BlockRealization realization) {
    public BlockTarget {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(realization, "realization");
    }
}
