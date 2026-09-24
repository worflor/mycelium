package mycelium.player_state.v1.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * An ordered set of single-step migrations. {@link #total} is a declared query over the registered steps;
 * {@link #upgrade} never trusts a declaration and always walks the real steps, throwing
 * {@link MigrationGapException} the moment a step is missing. A fixture may override {@code total()} to lie;
 * it cannot make {@code upgrade()} lie, because {@code upgrade()} does not consult it.
 */
public class MigrationChain {
    private final Map<Integer, Migration> byFrom;

    public MigrationChain(List<Migration> migrations) {
        Objects.requireNonNull(migrations, "migrations");
        Map<Integer, Migration> map = new HashMap<>();
        for (Migration m : migrations) {
            if (map.containsKey(m.from())) {
                throw new IllegalArgumentException("duplicate migration from schema " + m.from());
            }
            map.put(m.from(), m);
        }
        this.byFrom = Map.copyOf(map);
    }

    /** True if every step from {@code fromSchema} to {@code toSchema} is present and contiguous. */
    public boolean total(int fromSchema, int toSchema) {
        if (fromSchema == toSchema) {
            return true;
        }
        if (fromSchema > toSchema) {
            return false;
        }
        int cursor = fromSchema;
        while (cursor < toSchema) {
            Migration m = byFrom.get(cursor);
            if (m == null || m.to() <= cursor) {
                return false;
            }
            cursor = m.to();
        }
        return cursor == toSchema;
    }

    /** Idempotent at target: a blob already at {@code target} is returned unchanged. Always walks the real
     * registered steps; throws {@link MigrationGapException} if a step is missing, never a partial result. */
    public StateBlob upgrade(StateBlob blob, int target) {
        Objects.requireNonNull(blob, "blob");
        StateBlob current = blob;
        while (current.schema() != target) {
            Migration m = byFrom.get(current.schema());
            if (m == null) {
                throw new MigrationGapException(current.schema(), target);
            }
            StateBlob next = m.migrate(current);
            if (next.schema() <= current.schema()) {
                throw new MigrationGapException(current.schema(), target);
            }
            current = next;
        }
        return current;
    }

    protected final Migration stepFrom(int schema) {
        return byFrom.get(schema);
    }
}
