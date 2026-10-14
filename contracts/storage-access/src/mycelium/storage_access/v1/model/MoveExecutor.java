package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

/** Runs a move inside the caller's transaction; the reference is {@link StorageAccess#move}. */
@FunctionalInterface
public interface MoveExecutor {
    MoveOutcome move(StoragePort port, Move move, FlowTransaction tx, MoveFeedback feedback);
}
