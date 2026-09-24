package mycelium.world_copy.v1.model;

import java.util.ArrayList;
import java.util.List;

/** Transaction scope with parent/nested semantics. Same shape as inventory.transfer@1's FlowTransaction. */
public final class CopyTx implements AutoCloseable {
    public enum State { OPEN, COMMITTED, ABORTED }

    private final CopyTx parent;
    private final List<Runnable> undos = new ArrayList<>();
    private final List<Runnable> deferred = new ArrayList<>();
    private State state = State.OPEN;

    private CopyTx(CopyTx parent) {
        this.parent = parent;
    }

    public static CopyTx open() {
        return new CopyTx(null);
    }

    public CopyTx openNested() {
        requireOpen();
        return new CopyTx(this);
    }

    public boolean isOuter() {
        return parent == null;
    }

    public State state() {
        return state;
    }

    public void registerUndo(Runnable undo) {
        requireOpen();
        undos.add(undo);
    }

    public void onOuterCommit(Runnable action) {
        requireOpen();
        deferred.add(action);
    }

    public void commit() {
        requireOpen();
        state = State.COMMITTED;
        if (parent == null) {
            List<Runnable> actions = List.copyOf(deferred);
            deferred.clear();
            undos.clear();
            for (Runnable action : actions) {
                action.run();
            }
        } else {
            parent.adopt(undos, deferred);
            undos.clear();
            deferred.clear();
        }
    }

    public void abort() {
        if (state != State.OPEN) {
            return;
        }
        state = State.ABORTED;
        for (int i = undos.size() - 1; i >= 0; i--) {
            undos.get(i).run();
        }
        undos.clear();
        deferred.clear();
    }

    @Override
    public void close() {
        if (state == State.OPEN) {
            abort();
        }
    }

    private void adopt(List<Runnable> childUndos, List<Runnable> childDeferred) {
        undos.addAll(childUndos);
        deferred.addAll(childDeferred);
    }

    private void requireOpen() {
        if (state != State.OPEN) {
            throw new IllegalStateException("transaction is " + state);
        }
    }
}
