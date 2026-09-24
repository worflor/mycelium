package mycelium.dimension_travel.v2.model;

import java.util.Objects;

public record Position(DimensionId dimension, double x, double y, double z, float yaw, float pitch) {
    public Position {
        Objects.requireNonNull(dimension, "dimension");
    }
}
