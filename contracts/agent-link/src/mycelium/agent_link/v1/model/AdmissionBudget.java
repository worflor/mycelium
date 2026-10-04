package mycelium.agent_link.v1.model;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** A thread-safe count and encoded-byte budget for requests retained by an asynchronous link queue. */
public final class AdmissionBudget implements Admission {
    private final int maxJobs;
    private final long maxBytes;
    private int jobs;
    private long bytes;

    public AdmissionBudget(int maxJobs, long maxBytes) {
        if (maxJobs < 1 || maxBytes < 1) {
            throw new IllegalArgumentException("maxJobs and maxBytes must be positive");
        }
        this.maxJobs = maxJobs;
        this.maxBytes = maxBytes;
    }

    /**
     * Attempts one atomic reservation. The caller supplies the actual encoded frame-byte charge; this class never
     * retains request data or derives a cost from Java character counts.
     */
    @Override
    public synchronized Optional<Lease> tryAcquire(long frameBytes) {
        if (frameBytes <= 0 || frameBytes > maxBytes || jobs >= maxJobs || frameBytes > maxBytes - bytes) {
            return Optional.empty();
        }
        jobs++;
        bytes += frameBytes;
        return Optional.of(new Reservation(this, frameBytes));
    }

    private synchronized void release(long frameBytes) {
        if (jobs <= 0 || frameBytes <= 0 || frameBytes > bytes) {
            throw new IllegalStateException("admission reservation state is inconsistent");
        }
        jobs--;
        bytes -= frameBytes;
    }

    private static final class Reservation implements Lease {
        private final AdmissionBudget owner;
        private final long frameBytes;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Reservation(AdmissionBudget owner, long frameBytes) {
            this.owner = owner;
            this.frameBytes = frameBytes;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(frameBytes);
            }
        }
    }
}
