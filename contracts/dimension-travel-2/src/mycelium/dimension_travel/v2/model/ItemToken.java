package mycelium.dimension_travel.v2.model;

public record ItemToken(String id, int count) {
    public ItemToken {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("item id must be non-blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1");
        }
    }
}
