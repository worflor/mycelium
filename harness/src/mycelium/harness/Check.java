package mycelium.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Accumulates labeled failures. A label is a stable identifier such as {@code "T1:committed-7"};
 * rejection matrices refer to labels, so labels are part of a contract package's public surface.
 */
public final class Check {
    private final List<String> failures = new ArrayList<>();
    private int assertions;

    public void that(boolean condition, String label) {
        assertions++;
        if (!condition) {
            failures.add(label);
        }
    }

    public void equal(Object expected, Object actual, String label) {
        assertions++;
        if (!Objects.equals(expected, actual)) {
            failures.add(label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public void equalLong(long expected, long actual, String label) {
        assertions++;
        if (expected != actual) {
            failures.add(label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    /** Doubles within {@code tolerance} (kernels compare physics, odds and geometry, never bit patterns). */
    public void near(double expected, double actual, double tolerance, String label) {
        assertions++;
        if (Double.isNaN(actual) || Math.abs(expected - actual) > tolerance) {
            failures.add(label + " (expected " + expected + " +/- " + tolerance + ", got " + actual + ")");
        }
    }

    public void fail(String label) {
        assertions++;
        failures.add(label);
    }

    /** Runs a body that must throw; records a failure if it does not. */
    public void throwsAny(Runnable body, String label) {
        assertions++;
        try {
            body.run();
            failures.add(label + " (no exception)");
        } catch (RuntimeException expected) {
            // intended
        }
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    public int assertions() {
        return assertions;
    }

    public boolean clean() {
        return failures.isEmpty();
    }

    /** True if any failure label starts with the given prefix (labels may carry a detail suffix). */
    public boolean has(String labelPrefix) {
        for (String f : failures) {
            if (f.equals(labelPrefix) || f.startsWith(labelPrefix + " ")) {
                return true;
            }
        }
        return false;
    }
}
