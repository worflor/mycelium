package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Why a program, a catalog, a lock or a target was refused: a kind a tool can switch on, where in the input it is, and
 * words. A {@link Kind#LOCK_MISSING} diagnostic also carries the exact lock lines to add.
 */
public record Diagnostic(Kind kind, String where, String message, List<String> lockLines) {
    public enum Kind {
        /** Text that is not JSON. */
        SYNTAX,
        /** JSON of the wrong shape: a missing, unknown or mistyped field. */
        SCHEMA,
        /** An input names a component or a field the catalog does not have. */
        CATALOG,
        /** An input reads a component that is not network-visible (S5). */
        SERVER_PRIVATE,
        /** An input's domain does not fit the field it reads, or a guard compares the wrong kind. */
        TYPE,
        /** A value outside an input's domain, or a guard that can never match. */
        DOMAIN,
        /** A model template that names no input, is unbalanced, or does not expand to a model reference. */
        TEMPLATE,
        /** The domain product is above the cap (S7). */
        TOO_LARGE,
        /** The lock is malformed, or contradicts itself. */
        LOCK_INVALID,
        /** The lock lacks selections the program needs (S3): {@link #lockLines()} are the lines to add. */
        LOCK_MISSING,
        /** The target Minecraft version has no presentation mechanism. */
        NO_REALIZATION,
        /** A model reference collides with the model the target writes for the item itself. */
        MODEL_CLASH,
        /** A display context rule that is malformed, or names a model the program never draws. */
        CONTEXT
    }

    public Diagnostic {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(where, "where");
        Objects.requireNonNull(message, "message");
        lockLines = Collections.unmodifiableList(new ArrayList<>(lockLines));
    }

    public Diagnostic(Kind kind, String where, String message) {
        this(kind, where, message, Collections.<String>emptyList());
    }

    @Override
    public String toString() {
        return "[" + kind + "] " + where + ": " + message;
    }
}
