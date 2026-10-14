package mycelium.storage_access.v1.fixtures;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.Move;
import mycelium.storage_access.v1.model.MoveExecutor;
import mycelium.storage_access.v1.model.MoveFeedback;
import mycelium.storage_access.v1.model.MoveOutcome;
import mycelium.storage_access.v1.model.MoveReceipt;
import mycelium.storage_access.v1.model.Reason;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;

import java.util.Optional;

/**
 * Moves that each get one thing wrong: the reference {@code StorageAccess.move}, copied, with one flaw switched on, so each
 * differs from the reference in exactly the way its name says.
 */
public final class ExecutorVariants {
    private ExecutorVariants() {
    }

    private enum Flaw {
        /** Handles items only: a fluid move is reported unsupported although the port reaches fluids. */
        TIMID,
        /** Never asks whether both addresses are one store: a chest's top face moves goods to its own side. */
        SELF_MOVE,
        /** Inserts in a transaction of its own, committed at once, and extracts in the caller's: not one commit. */
        SPLIT_COMMIT,
        /** The receipt says what was planned from the simulations, not what the destination took. */
        CLAIMING,
        /** When the destination takes less than was extracted, commits anyway: the remainder is lost. */
        NO_RELEASE,
        /** Calls feedback as soon as the move holds, before the outermost commit. */
        EARLY_FEEDBACK,
        /** Never calls feedback. */
        SILENT,
        /** Never asks whether a store has vanished before committing. */
        UNWATCHFUL
    }

    public static MoveExecutor timid() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.TIMID);
    }

    public static MoveExecutor selfMove() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.SELF_MOVE);
    }

    public static MoveExecutor splitCommit() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.SPLIT_COMMIT);
    }

    public static MoveExecutor claiming() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.CLAIMING);
    }

    public static MoveExecutor noRelease() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.NO_RELEASE);
    }

    public static MoveExecutor earlyFeedback() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.EARLY_FEEDBACK);
    }

    public static MoveExecutor silent() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.SILENT);
    }

    public static MoveExecutor unwatchful() {
        return (p, m, tx, f) -> move(p, m, tx, f, Flaw.UNWATCHFUL);
    }

    private static MoveOutcome move(StoragePort port, Move move, FlowTransaction tx, MoveFeedback feedback, Flaw flaw) {
        if (move.amount() <= 0) {
            return new MoveOutcome.Rejected(Reason.ZERO_REQUEST);
        }
        if (flaw != Flaw.SELF_MOVE && move.from().sameStoreAs(move.to())) {
            return new MoveOutcome.Rejected(Reason.SAME_STORE);
        }
        Goods goods = move.goods();
        if (!port.kinds().contains(goods.kind()) || (flaw == Flaw.TIMID && goods.kind() == Kind.FLUID)) {
            return new MoveOutcome.Unsupported("the port reaches no " + goods.kind() + " storage");
        }
        Optional<Store> foundSource = port.find(move.from(), goods.kind());
        if (foundSource.isEmpty() || !foundSource.get().present()) {
            return new MoveOutcome.Rejected(Reason.NO_SOURCE);
        }
        Optional<Store> foundDestination = port.find(move.to(), goods.kind());
        if (foundDestination.isEmpty() || !foundDestination.get().present()) {
            return new MoveOutcome.Rejected(Reason.NO_DESTINATION);
        }
        Store source = foundSource.get();
        Store destination = foundDestination.get();
        long available = source.simulateExtract(goods, move.amount());
        long room = destination.simulateInsert(goods, move.amount());
        if (available <= 0) {
            return new MoveOutcome.Rejected(Reason.EMPTY_SOURCE);
        }
        if (room <= 0) {
            return new MoveOutcome.Rejected(Reason.FULL_DESTINATION);
        }
        long first = Math.min(move.amount(), Math.min(available, room));
        long planned = first;
        for (int attempt = 0; attempt < 2; attempt++) {
            FlowTransaction scope = tx.openNested();
            long extracted = source.extract(goods, planned, scope);
            StoreAddress gone = flaw == Flaw.UNWATCHFUL ? null : vanished(source, destination);
            if (gone != null) {
                scope.abort();
                return new MoveOutcome.Stale(gone);
            }
            if (extracted <= 0 || extracted > planned) {
                scope.abort();
                return new MoveOutcome.Rejected(Reason.EXTRACTION_FAILED);
            }
            FlowTransaction own = flaw == Flaw.SPLIT_COMMIT ? FlowTransaction.open() : scope;
            long inserted = destination.insert(goods, extracted, own);
            if (own != scope) {
                own.commit();
            }
            gone = flaw == Flaw.UNWATCHFUL ? null : vanished(source, destination);
            if (gone != null) {
                scope.abort();
                return new MoveOutcome.Stale(gone);
            }
            if (inserted == extracted || (flaw == Flaw.NO_RELEASE && inserted > 0 && inserted < extracted)) {
                scope.commit();
                long reported = flaw == Flaw.CLAIMING ? first : inserted;
                MoveReceipt receipt = new MoveReceipt(move.from(), move.to(), goods, move.amount(), reported);
                if (flaw == Flaw.EARLY_FEEDBACK) {
                    feedback.moved(receipt);
                } else if (flaw != Flaw.SILENT) {
                    tx.onOuterCommit(() -> feedback.moved(receipt));
                }
                return new MoveOutcome.Moved(receipt);
            }
            scope.abort();
            if (inserted <= 0 || inserted > extracted) {
                return new MoveOutcome.Rejected(Reason.INSERTION_FAILED);
            }
            planned = inserted;
        }
        return new MoveOutcome.Rejected(Reason.TRANSFER_FAILED);
    }

    private static StoreAddress vanished(Store source, Store destination) {
        if (!source.present()) {
            return source.address();
        }
        if (!destination.present()) {
            return destination.address();
        }
        return null;
    }
}
