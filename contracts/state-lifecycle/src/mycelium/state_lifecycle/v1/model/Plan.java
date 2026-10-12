package mycelium.state_lifecycle.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * An ordered list of effects for one transition: what the platform will do, as data. Two plans are equal when they are
 * the same transition with the same effects in the same order (L7).
 */
public record Plan<H>(TransitionId transition, List<Effect> effects) {
    public Plan {
        Objects.requireNonNull(transition, "transition");
        effects = Collections.unmodifiableList(new ArrayList<>(effects));
    }
}
