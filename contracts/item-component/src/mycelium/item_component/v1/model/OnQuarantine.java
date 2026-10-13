package mycelium.item_component.v1.model;

/**
 * What a write does when it meets data it cannot read (an unreadable fenced envelope, a component entry that does not
 * decode, an old key of a migration that does not decode). The anti-data-loss guarantee (T5) holds in {@code REFUSE} and
 * {@code KEEP}; only {@code REPLACE} may discard, and only because the adapter said so.
 */
public enum OnQuarantine {
    /** Change nothing and say why ({@link WriteOutcome.Refused}). The default: the adapter decides what to do. */
    REFUSE,
    /** Apply the write and keep the unreadable data beside it, as recovery records under the fence. */
    KEEP,
    /** Apply the write and discard the unreadable data it replaces. */
    REPLACE
}
