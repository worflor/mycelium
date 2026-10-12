package mycelium.item_presentation.v1.model;

import java.util.Objects;

/**
 * What a program is compiled for: the Minecraft version, named only so a refusal can say it, and the realization the
 * era table gives that version. The model reads the realization and never the version.
 */
public record Target(String minecraft, Realization realization) {
    public Target {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(realization, "realization");
    }
}
