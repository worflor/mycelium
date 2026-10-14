package mycelium.data_generation.v1.model;

import java.util.Comparator;

/**
 * One entry of one kind: the kind's id and the entry's catalog id ({@code ns:path}). Both are non-empty, well-formed
 * UTF-16 (no unpaired surrogate), so their UTF-8 bytes name them exactly. Refs order by kind, then id, each compared by
 * Unicode code point, which is the order of their UTF-8 bytes; this is the order the hash lists entries in, and it does
 * not depend on any map, list or locale.
 */
public record Ref(String kind, String id) implements Comparable<Ref> {
    /** By kind, then id, by code point (UTF-8 byte order). */
    public static final Comparator<Ref> ORDER = Comparator.comparing(Ref::kind, Ref::compareCodePoints)
            .thenComparing(Ref::id, Ref::compareCodePoints);

    public Ref {
        wellFormed(kind, "kind");
        wellFormed(id, "id");
    }

    @Override
    public int compareTo(Ref other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return kind + "/" + id;
    }

    /** Code point order: for well-formed strings, exactly the order of their UTF-8 bytes compared unsigned. */
    public static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return Integer.compare(x, y);
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    static void wellFormed(String s, String what) {
        if (s == null || s.isEmpty()) {
            throw new IllegalArgumentException(what + " must be a non-empty string");
        }
        Hashing.requireWellFormed(s, what);
    }
}
