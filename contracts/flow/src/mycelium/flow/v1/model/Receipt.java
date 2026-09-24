package mycelium.flow.v1.model;

public record Receipt(ResourceKey resource, long requested, long committed) {
    public Receipt {
        if (resource == null) {
            throw new IllegalArgumentException("resource");
        }
        if (requested < 1) {
            throw new IllegalArgumentException("requested must be >= 1");
        }
        if (committed < 1) {
            throw new IllegalArgumentException("committed must be >= 1; zero movement is a rejection");
        }
        if (committed > requested) {
            throw new IllegalArgumentException("committed must not exceed requested");
        }
    }
}
