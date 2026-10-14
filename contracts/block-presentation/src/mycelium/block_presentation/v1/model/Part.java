package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.Case;
import mycelium.item_presentation.v1.model.Condition;
import mycelium.item_presentation.v1.model.Template;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * One piece of a block's picture: a model that is drawn whenever its guard holds, turned a quarter-turn multiple about the
 * block's vertical axis. {@code model} is a model reference or a template over input values, exactly as a Sigil case's is.
 * A part with an empty guard is always drawn (the core of the block).
 */
public record Part(Map<String, Condition> when, String model, int turn) {
    /** The turns a part may take, in degrees clockwise seen from above. */
    public static final List<Integer> TURNS = List.of(0, 90, 180, 270);

    public Part {
        Objects.requireNonNull(model, "model");
        if (!TURNS.contains(turn)) {
            throw new IllegalArgumentException("a turn is one of " + TURNS + " degrees, not " + turn);
        }
        when = Collections.unmodifiableMap(new TreeMap<>(when));
    }

    /** The Sigil case this part's guard and model are: the guard language is the Sigil's, not a second one. */
    public Case asCase() {
        return new Case(when, model);
    }

    /** Whether the part is drawn for these input values. */
    public boolean holds(Map<String, Object> tuple) {
        return asCase().matches(tuple);
    }

    /** The names of the inputs this part reads, in its guard and in its model template, sorted. */
    public List<String> reads() {
        TreeSet<String> names = new TreeSet<>(when.keySet());
        names.addAll(Template.placeholders(model));
        return List.copyOf(names);
    }
}
