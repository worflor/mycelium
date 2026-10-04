package mycelium.agent_link.v1.harness;

import mycelium.agent_link.v1.model.Admission;
import mycelium.agent_link.v1.model.AdmissionBudget;
import mycelium.harness.Check;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Admission obligations for the asynchronous queue seam of agent.link@1. */
public final class AdmissionContractTests {
    @FunctionalInterface
    public interface Budgets {
        Admission make(int maxJobs, long maxBytes);
    }

    private final Budgets budgets;

    public AdmissionContractTests(Budgets budgets) {
        this.budgets = budgets;
    }

    public Check run() {
        Check check = new Check();
        admitsAvailableWork(check);
        boundsJobs(check);
        boundsBytes(check);
        rejectsCostsWithoutCharging(check);
        releasesExactlyOnce(check);
        closesAcrossWorkOutcomes(check);
        concurrentReservationsAreAtomic(check);
        invalidCeilingsRefuse(check);
        return check;
    }

    private void admitsAvailableWork(Check check) {
        Admission admission = budgets.make(1, 1L << 20);
        long emojiFrameBytes = "{\"message\":\"🌿\"}".getBytes(StandardCharsets.UTF_8).length;
        Optional<Admission.Lease> lease = admission.tryAcquire(emojiFrameBytes);
        boolean accepted = lease.isPresent();
        if (lease.isPresent()) {
            lease.get().close();
        }
        check.that(accepted, "P5:admits-available-work");
    }

    private void boundsJobs(Check check) {
        Admission admission = budgets.make(1, 100);
        Optional<Admission.Lease> first = admission.tryAcquire(40);
        Optional<Admission.Lease> second = admission.tryAcquire(1);
        boolean bounded = first.isPresent() && !second.isPresent();
        close(first);
        close(second);
        check.that(bounded, "L11:bounded-admission");
    }

    private void boundsBytes(Check check) {
        Admission admission = budgets.make(4, 1L << 20);
        Optional<Admission.Lease> exact = admission.tryAcquire(1L << 20);
        Optional<Admission.Lease> over = admission.tryAcquire(1);
        boolean bounded = exact.isPresent() && !over.isPresent();
        close(exact);
        close(over);

        Admission split = budgets.make(4, 10);
        Optional<Admission.Lease> a = split.tryAcquire(6);
        Optional<Admission.Lease> b = split.tryAcquire(4);
        Optional<Admission.Lease> c = split.tryAcquire(1);
        boolean sumBounded = a.isPresent() && b.isPresent() && !c.isPresent();
        close(a);
        close(b);
        close(c);
        check.that(bounded && sumBounded, "L11:bounded-admission");
    }

    private void rejectsCostsWithoutCharging(Check check) {
        Admission admission = budgets.make(1, 8);
        boolean rejected = !admission.tryAcquire(0).isPresent()
                && !admission.tryAcquire(-1).isPresent()
                && !admission.tryAcquire(9).isPresent()
                && !admission.tryAcquire(Long.MAX_VALUE).isPresent();
        Optional<Admission.Lease> valid = admission.tryAcquire(8);
        boolean notCharged = valid.isPresent();
        close(valid);
        check.that(rejected && notCharged, "L12:admission-release");
    }

    private void releasesExactlyOnce(Check check) {
        Admission admission = budgets.make(2, 20);
        Optional<Admission.Lease> first = admission.tryAcquire(10);
        Optional<Admission.Lease> second = admission.tryAcquire(10);
        boolean filled = first.isPresent() && second.isPresent() && !admission.tryAcquire(1).isPresent();
        if (first.isPresent()) {
            first.get().close();
            first.get().close();
        }
        Optional<Admission.Lease> third = admission.tryAcquire(10);
        Optional<Admission.Lease> fourth = admission.tryAcquire(1);
        boolean oneRelease = filled && third.isPresent() && !fourth.isPresent();
        close(second);
        close(third);
        Optional<Admission.Lease> after = admission.tryAcquire(20);
        boolean reopened = after.isPresent();
        close(after);
        check.that(oneRelease && reopened, "L12:admission-release");
    }

    private void closesAcrossWorkOutcomes(Check check) {
        boolean success = completesAndReopens(WorkEnd.SUCCESS);
        boolean handlerFailure = completesAndReopens(WorkEnd.HANDLER_FAILURE);
        boolean cancellation = completesAndReopens(WorkEnd.CANCELLED);
        boolean queueRejected = completesAndReopens(WorkEnd.QUEUE_REJECTED);
        check.that(success && handlerFailure && cancellation && queueRejected, "L12:admission-release");
    }

    private enum WorkEnd { SUCCESS, HANDLER_FAILURE, CANCELLED, QUEUE_REJECTED }

    private boolean completesAndReopens(WorkEnd outcome) {
        Admission admission = budgets.make(1, 16);
        Optional<Admission.Lease> acquired = admission.tryAcquire(16);
        if (!acquired.isPresent()) {
            return false;
        }
        int[] handlerCalls = {0};
        try {
            if (outcome == WorkEnd.SUCCESS || outcome == WorkEnd.HANDLER_FAILURE) {
                handlerCalls[0]++;
            }
            if (outcome == WorkEnd.HANDLER_FAILURE) {
                throw new IllegalStateException("handler failed");
            }
        } catch (IllegalStateException expected) {
            // The owner closes the lease for failed work as well as successful work.
        } finally {
            acquired.get().close();
        }
        Optional<Admission.Lease> reopened = admission.tryAcquire(16);
        boolean ok = reopened.isPresent();
        close(reopened);
        int expectedCalls = outcome == WorkEnd.SUCCESS || outcome == WorkEnd.HANDLER_FAILURE ? 1 : 0;
        return ok && handlerCalls[0] == expectedCalls;
    }

    private void concurrentReservationsAreAtomic(Check check) {
        Admission admission = budgets.make(4, 12);
        int count = 16;
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(count);
        List<Admission.Lease> leases = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger accepted = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) {
                        failure.compareAndSet(null, new IllegalStateException("start barrier timed out"));
                        return;
                    }
                    Optional<Admission.Lease> lease = admission.tryAcquire(3);
                    if (lease.isPresent()) {
                        leases.add(lease.get());
                        accepted.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.compareAndSet(null, e);
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            }, "agent-link-admission-race-" + i);
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        boolean allReady = await(ready, 3, TimeUnit.SECONDS);
        start.countDown();
        boolean allDone = await(done, 3, TimeUnit.SECONDS);
        boolean joined = joinWithin(threads, 2, TimeUnit.SECONDS);
        if (!joined) {
            for (Thread thread : threads) {
                if (thread.isAlive()) {
                    thread.interrupt();
                }
            }
            joined = joinWithin(threads, 2, TimeUnit.SECONDS) && joined;
        }
        int admitted = accepted.get();
        synchronized (leases) {
            for (Admission.Lease lease : leases) {
                lease.close();
            }
        }
        for (Thread thread : threads) {
            if (thread.isAlive()) {
                thread.interrupt();
            }
        }
        Optional<Admission.Lease> reopened = admission.tryAcquire(12);
        boolean reopenedFully = reopened.isPresent();
        close(reopened);
        check.that(allReady && allDone && joined && failure.get() == null && admitted == 4 && reopenedFully,
                "L11:bounded-admission");
    }

    private void invalidCeilingsRefuse(Check check) {
        boolean jobsRejected = throwsIllegalArgument(() -> new AdmissionBudget(0, 1));
        boolean bytesRejected = throwsIllegalArgument(() -> new AdmissionBudget(1, 0));
        check.that(jobsRejected && bytesRejected, "L11:bounded-admission");
    }

    private static boolean throwsIllegalArgument(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void close(Optional<Admission.Lease> lease) {
        if (lease.isPresent()) {
            lease.get().close();
        }
    }

    private static boolean await(CountDownLatch latch, long time, TimeUnit unit) {
        try {
            return latch.await(time, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean joinWithin(List<Thread> threads, long time, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(time);
        boolean joined = true;
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    long millis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
                    thread.join(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            joined &= !thread.isAlive();
        }
        return joined;
    }
}
