package mycelium.agent_link.v1.fixtures;

import mycelium.agent_link.v1.model.Admission;
import mycelium.agent_link.v1.model.AdmissionBudget;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Deliberately incorrect request-admission implementations for the laws in SPEC.md. */
public final class AdmissionFixtures {
    private AdmissionFixtures() {
    }

    public static final class NoOp implements Admission {
        @Override
        public Optional<Lease> tryAcquire(long frameBytes) {
            return Optional.empty();
        }
    }

    /** Enforces bytes but ignores the job ceiling. */
    public static final class JobOverbook implements Admission {
        private final AdmissionBudget delegate;

        public JobOverbook(int maxJobs, long maxBytes) {
            delegate = new AdmissionBudget(Integer.MAX_VALUE, maxBytes);
        }

        @Override
        public Optional<Lease> tryAcquire(long frameBytes) {
            return delegate.tryAcquire(frameBytes);
        }
    }

    /** Enforces jobs but ignores the aggregate byte ceiling. */
    public static final class ByteOverbook implements Admission {
        private final AdmissionBudget delegate;

        public ByteOverbook(int maxJobs, long maxBytes) {
            delegate = new AdmissionBudget(maxJobs, Long.MAX_VALUE);
        }

        @Override
        public Optional<Lease> tryAcquire(long frameBytes) {
            return delegate.tryAcquire(frameBytes);
        }
    }

    /** Charges an invalid over-ceiling frame even though it refuses that frame. */
    public static final class RejectedCostLeak implements Admission {
        private final int maxJobs;
        private final long maxBytes;
        private int jobs;
        private long bytes;

        public RejectedCostLeak(int maxJobs, long maxBytes) {
            this.maxJobs = maxJobs;
            this.maxBytes = maxBytes;
        }

        @Override
        public synchronized Optional<Lease> tryAcquire(long frameBytes) {
            if (frameBytes <= 0 || frameBytes > maxBytes) {
                if (frameBytes > maxBytes && jobs < maxJobs) {
                    jobs++;
                    bytes++;
                }
                return Optional.empty();
            }
            if (jobs >= maxJobs || frameBytes > maxBytes - bytes) {
                return Optional.empty();
            }
            jobs++;
            bytes += frameBytes;
            return Optional.of(new Lease() {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        release(frameBytes);
                    }
                }
            });
        }

        private synchronized void release(long frameBytes) {
            jobs--;
            bytes -= frameBytes;
        }
    }

    /** Returns a valid delegate reservation whose close forgets to release it. */
    public static final class LeakyLease implements Admission {
        private final AdmissionBudget delegate;

        public LeakyLease(int maxJobs, long maxBytes) {
            delegate = new AdmissionBudget(maxJobs, maxBytes);
        }

        @Override
        public Optional<Lease> tryAcquire(long frameBytes) {
            Optional<Lease> reservation = delegate.tryAcquire(frameBytes);
            if (!reservation.isPresent()) {
                return Optional.empty();
            }
            return Optional.of(() -> { });
        }
    }

    /** Two callers both observe the last slot before either publishes its increment. */
    public static final class Racing implements Admission {
        private final int maxJobs;
        private final long maxBytes;
        private final CountDownLatch bothChecked = new CountDownLatch(2);
        private int jobs;
        private long bytes;

        public Racing(int maxJobs, long maxBytes) {
            this.maxJobs = maxJobs;
            this.maxBytes = maxBytes;
        }

        @Override
        public Optional<Lease> tryAcquire(long frameBytes) {
            int checkedJobs = jobs;
            long checkedBytes = bytes;
            if (frameBytes <= 0 || frameBytes > maxBytes || checkedJobs >= maxJobs
                    || frameBytes > maxBytes - checkedBytes) {
                return Optional.empty();
            }
            bothChecked.countDown();
            try {
                if (!bothChecked.await(2, TimeUnit.SECONDS)) {
                    return Optional.empty();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            jobs = checkedJobs + 1;
            bytes = checkedBytes + frameBytes;
            return Optional.of(new Lease() {
                private final AtomicBoolean closed = new AtomicBoolean();

                @Override
                public void close() {
                    if (closed.compareAndSet(false, true)) {
                        synchronized (Racing.this) {
                            jobs = Math.max(0, jobs - 1);
                            bytes = Math.max(0, bytes - frameBytes);
                        }
                    }
                }
            });
        }
    }

}
