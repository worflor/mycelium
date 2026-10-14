package mycelium.data_generation.v1.model;

/** How much a finding matters. Only {@link #ERROR} breaks an entry or a candidate; the rest are carried. */
public enum Severity {
    /** The entry (or, with no subject, the candidate) is broken: its kind's {@link Policy} decides what follows. */
    ERROR,
    /** Likely a mistake, published anyway. */
    WARNING,
    /** For the log. */
    INFO
}
