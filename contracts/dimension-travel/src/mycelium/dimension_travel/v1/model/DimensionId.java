package mycelium.dimension_travel.v1.model;

public record DimensionId(String id) {
    public DimensionId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("dimension id must be non-blank");
        }
    }
}
