package mycelium.data_catalog.v1.model;

import java.util.Objects;

/**
 * One data file offered to a catalog: its id ({@code ns:path}), its raw text, and the order of the pack it came from
 * (a later pack overrides an earlier one on the same id).
 */
public record Source(String id, String text, int packOrder) {
    public Source {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        if (id.isBlank()) {
            throw new IllegalArgumentException("a source needs an id");
        }
    }
}
