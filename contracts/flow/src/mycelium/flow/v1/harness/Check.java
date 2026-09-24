package mycelium.flow.v1.harness;

import java.util.ArrayList;
import java.util.List;

public final class Check {
    private final List<String> failures = new ArrayList<>();

    public void that(boolean condition, String label) {
        if (!condition) {
            failures.add(label);
        }
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    public boolean clean() {
        return failures.isEmpty();
    }
}
