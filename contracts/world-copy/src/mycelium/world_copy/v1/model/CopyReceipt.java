package mycelium.world_copy.v1.model;

/** What actually committed. Zero writes is a rejection, never a receipt. */
public record CopyReceipt(int writes, int sanitized) {
    public CopyReceipt {
        if (writes < 1) {
            throw new IllegalArgumentException("writes must be >= 1; zero writes is a rejection");
        }
        if (sanitized < 0 || sanitized > writes) {
            throw new IllegalArgumentException("sanitized must be within [0, writes]");
        }
    }
}
