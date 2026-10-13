package mycelium.item_component.v1.model;

/**
 * The carrier on a stack's root compound (on a modern stack, {@code minecraft:custom_data}): the one place an adapter
 * reads and writes components, as pure operations over {@link TagNode.TagCompound}. The adapter converts the platform's
 * compound to a {@code TagNode} and back; everything else (the fence, default elision, quarantine, migration of old
 * keys) is here, so it is the same on every version and loader.
 *
 * <p>Reads: {@link #read(TagNode.TagCompound)} is the whole snapshot and {@link #read(TagNode.TagCompound, ComponentDefinition)}
 * one component, by the same path with the same quarantine semantics (T15). Both apply the {@link LegacyKeyMigration}.
 * Writes return a {@link WriteOutcome}. Keys of the root that are neither the fence nor an old key of the migration are
 * never touched (T20).
 */
public interface FencedCarrier {
    ComponentRegistry registry();

    LegacyKeyMigration migration();

    /** Everything the root carries: the fenced snapshot, with old keys migrated in (T16). Never throws on stored data. */
    CarrierSnapshot read(TagNode.TagCompound root);

    /** One component of the root, by the same decode path as {@link #read(TagNode.TagCompound)} (T15). */
    <T> ComponentRead<T> read(TagNode.TagCompound root, ComponentDefinition<T> def);

    /**
     * Stores a snapshot on the root: the fence replaced, old keys the snapshot accounts for removed (T17), all other keys
     * kept. Unreadable data in the root the snapshot would destroy is handled as {@code mode} says (T14).
     */
    WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot, OnQuarantine mode);

    /** Puts one component on the root, keeping everything else it carries. */
    <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value, OnQuarantine mode);

    /** Takes one component off the root; with nothing else left to carry the root is again as a plain one's. */
    WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def, OnQuarantine mode);

    default WriteOutcome write(TagNode.TagCompound root, CarrierSnapshot snapshot) {
        return write(root, snapshot, OnQuarantine.REFUSE);
    }

    default <T> WriteOutcome set(TagNode.TagCompound root, ComponentDefinition<T> def, T value) {
        return set(root, def, value, OnQuarantine.REFUSE);
    }

    default WriteOutcome clear(TagNode.TagCompound root, ComponentDefinition<?> def) {
        return clear(root, def, OnQuarantine.REFUSE);
    }

    /** The standard implementation, with no old keys to migrate. */
    static FencedCarrier standard(ComponentRegistry registry) {
        return StandardFencedCarrier.of(registry, LegacyKeyMigration.none());
    }

    static FencedCarrier standard(ComponentRegistry registry, LegacyKeyMigration migration) {
        return StandardFencedCarrier.of(registry, migration);
    }
}
