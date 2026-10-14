package mycelium.data_generation.v1.model;

import java.util.Comparator;
import java.util.Objects;

/**
 * One finding: how much it matters, which entry it is about ({@code subject}; {@code null} for the candidate as a whole),
 * where in the entry ({@code path}, empty for all of it), a stable {@code code} ({@code reference:dangling}, or an
 * assembler's own such as {@code cut.careful-not-zero}) and the message a data author reads.
 */
public record Diagnostic(Severity severity, Ref subject, String path, String code, String message) {
    /** Candidate findings first, then by subject, code, path and message: one order for every report. */
    public static final Comparator<Diagnostic> ORDER = Comparator
            .comparing(Diagnostic::subject, Comparator.nullsFirst(Ref.ORDER))
            .thenComparing(Diagnostic::code)
            .thenComparing(Diagnostic::path)
            .thenComparing(Diagnostic::message)
            .thenComparing(Diagnostic::severity);

    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        path = path == null ? "" : path;
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("a diagnostic needs a code");
        }
    }

    /** A finding about one entry, all of it. */
    public static Diagnostic of(Severity severity, Ref subject, String code, String message) {
        return new Diagnostic(severity, Objects.requireNonNull(subject, "subject"), "", code, message);
    }

    /** A finding about the candidate as a whole. */
    public static Diagnostic candidate(Severity severity, String code, String message) {
        return new Diagnostic(severity, null, "", code, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
