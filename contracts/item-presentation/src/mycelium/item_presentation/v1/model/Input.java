package mycelium.item_presentation.v1.model;

import java.util.Objects;

/**
 * A named reading of one component field, with the finite domain of values it can take. {@code field} is empty for a
 * {@link Domain.Present} input (which reads whether a unit component is on the stack) and a dotted path inside the
 * component's record otherwise. {@code fallback} is what the input is when the component or field is absent, so that
 * a stack carrying nothing still evaluates; it is the domain's first value unless the program says otherwise.
 */
public record Input(String name, String component, String field, Domain domain, Object fallback) {
    public Input {
        if (!Ids.name(name)) {
            throw new IllegalArgumentException("'" + name + "' is not an input name (lower-case letters, digits, '_', starting with a letter)");
        }
        if (!Ids.resource(component)) {
            throw new IllegalArgumentException("'" + component + "' is not a component id (namespace:path)");
        }
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(domain, "domain");
        boolean unit = domain instanceof Domain.Present;
        if (unit != field.isEmpty()) {
            throw new IllegalArgumentException(unit ? "a 'present' input reads a component, not a field of it"
                    : "input '" + name + "' needs the path of the field it reads");
        }
        if (!unit && !Ids.field(field)) {
            throw new IllegalArgumentException("'" + field + "' is not a field path");
        }
        if (fallback == null) {
            fallback = domain.first();
        }
        if (!domain.admits(fallback)) {
            throw new IllegalArgumentException("input '" + name + "': the default " + fallback + " is not in its domain");
        }
    }
}
