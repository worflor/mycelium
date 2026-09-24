package mycelium.harness;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * A named list of fixtures run against one {@link Check}. Used by kernels and by contract reference runs.
 * Fixture bodies must be deterministic; a fixture that throws is recorded as a failure with the exception text.
 */
public final class Suite {
    private record Fixture(String name, Consumer<Check> body) {
    }

    private final String title;
    private final List<Fixture> fixtures = new ArrayList<>();

    public Suite(String title) {
        this.title = title;
    }

    public Suite add(String name, Consumer<Check> body) {
        fixtures.add(new Fixture(name, body));
        return this;
    }

    public String title() {
        return title;
    }

    public int size() {
        return fixtures.size();
    }

    /**
     * Runs every fixture, or with {@code -Dmycelium.only=a,b} only those whose name contains one of the fragments
     * (for iterating on one law; a filtered run prints that it was filtered and is never a full result).
     */
    public Check run() {
        Check check = new Check();
        String only = System.getProperty("mycelium.only", "").trim();
        for (Fixture fixture : fixtures) {
            if (!only.isEmpty() && java.util.Arrays.stream(only.split(",")).map(String::trim).noneMatch(fixture.name()::contains)) {
                continue;
            }
            try {
                fixture.body().accept(check);
            } catch (RuntimeException | AssertionError e) {
                check.fail(fixture.name() + ":threw " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : " " + e.getMessage()));
            }
        }
        return check;
    }

    /** Prints a report and returns the exit code (number of failures). */
    public int report() {
        Check check = run();
        System.out.println(title);
        String only = System.getProperty("mycelium.only", "").trim();
        if (!only.isEmpty()) {
            System.out.println("  (filtered to fixtures matching: " + only + "; not a full result)");
        }
        System.out.println("  fixtures: " + fixtures.size() + ", assertions: " + check.assertions()
                + ", failures: " + check.failures().size());
        for (String failure : check.failures()) {
            System.out.println("  - " + failure);
        }
        System.out.println("  " + (check.clean() ? "PASS" : "FAIL"));
        return check.failures().size();
    }
}
