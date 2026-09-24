package mycelium.player_state.v1.fixtures;

import mycelium.player_state.v1.model.Migration;
import mycelium.player_state.v1.model.MigrationChain;

import java.util.List;

/** Declares {@code total()} true unconditionally (a lie) while its registered steps actually skip a
 * version. The executor must not trust the declaration: it walks the real steps in {@code upgrade()} and
 * must still surface {@code Rejected(SCHEMA_GAP)} instead of a partial upgrade. */
public final class SkippingMigrationChain extends MigrationChain {
    public SkippingMigrationChain(List<Migration> migrations) {
        super(migrations);
    }

    @Override
    public boolean total(int fromSchema, int toSchema) {
        return true;
    }
}
