package mycelium.item_component.v1.fixtures;

import mycelium.item_component.v1.model.*;

/**
 * Broken fixture: the migration table is accepted and never applied, so a stack with only old keys reads as plain. Violates
 * Law T16 (migrated values equal what the table says).
 */
public final class IgnoringMigrationCarrier extends DelegatingCarrier {
    public IgnoringMigrationCarrier(ComponentRegistry registry, LegacyKeyMigration migration) {
        super(registry, LegacyKeyMigration.none());
    }
}
