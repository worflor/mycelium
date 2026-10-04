package mycelium.agent_link.v1.model;

import java.util.Optional;

/**
 * Bounds requests retained by a consumer's asynchronous link queue. The consumer supplies the encoded frame's byte
 * cost before retaining the request and closes the returned lease when that work completes or is discarded.
 */
public interface Admission {
    /** Empty means the request must not be retained or passed to {@link Link#receive(Request)} yet. */
    Optional<Lease> tryAcquire(long frameBytes);

    /** One reservation, released at most once. */
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
