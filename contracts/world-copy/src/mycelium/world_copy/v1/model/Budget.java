package mycelium.world_copy.v1.model;

/** Maximum number of block writes one execution may perform. */
public record Budget(int maxWritesPerCall) {
    public Budget {
        if (maxWritesPerCall < 1) {
            throw new IllegalArgumentException("budget must be >= 1");
        }
    }
}
