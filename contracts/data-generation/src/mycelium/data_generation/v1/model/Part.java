package mycelium.data_generation.v1.model;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * What the assembler read from one offered entry: its ref, its canonical {@code content} (a text in which every
 * difference is a difference in what the entry does: for JSON data, its canonical JSON), and every entry it refers to.
 * The contract hashes the content and resolves the references; it never reads the content.
 */
public record Part(Ref ref, String content, SortedSet<Ref> references) {
    public Part {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(content, "content");
        Hashing.requireWellFormed(content, "content of " + ref);
        references = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(references, "references")));
    }

    public static Part of(Ref ref, String content, Set<Ref> references) {
        return new Part(ref, content, new TreeSet<>(references));
    }
}
