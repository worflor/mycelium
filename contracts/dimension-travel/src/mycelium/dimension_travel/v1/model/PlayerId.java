package mycelium.dimension_travel.v1.model;

public record PlayerId(String id) {
    public PlayerId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("player id must be non-blank");
        }
    }
}
