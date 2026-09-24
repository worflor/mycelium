package mycelium.rule_effects.v1.model;

import java.util.Set;

/**
 * A provider that can apply and remove effects on entities. Mutation happens only inside a transaction and
 * every mutation registers an undo. {@link #active(EntityId)} is the observable truth the harness compares
 * against; a port that lies here is caught by the fixtures, not trusted.
 */
public interface EffectPort {
    /** Applies (or replaces, if the id is already present) the effect. Returns false if the provider cannot. */
    boolean apply(EntityId entity, EffectSpec effect, Tx tx);

    /** Removes the effect tracked under {@code id}. Returns false if the provider cannot. */
    boolean remove(EntityId entity, String id, Tx tx);

    /** The ids currently active on the entity. Never null. */
    Set<String> active(EntityId entity);

    /** Fixture hook: tells the port an entity exists (so scope violations are observable). */
    default void know(EntityId entity) {
        throw new UnsupportedOperationException("know not supported by this port");
    }
}
