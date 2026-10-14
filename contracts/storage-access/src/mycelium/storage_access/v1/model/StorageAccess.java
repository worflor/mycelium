package mycelium.storage_access.v1.model;

import mycelium.flow.v1.model.FlowTransaction;

import java.util.Objects;
import java.util.Optional;

/**
 * The reference move (SPEC §3 to §6): a stroke that takes goods from one store and gives them to another, simulated and
 * then committed or released inside one nested scope of the caller's {@code inventory.transfer@1} transaction, so it moves
 * goods from one store to the other or moves nothing.
 *
 * <ol>
 *   <li>Refuse what can be refused before looking: zero, one store twice, a kind the port does not reach.</li>
 *   <li>Find both stores by level, position and face; refuse a missing one.</li>
 *   <li>Simulate the extraction and the insertion; plan the least of the request, what the source gives and what the
 *       destination takes.</li>
 *   <li>Open one nested scope of the caller's transaction; extract, then insert what was extracted.</li>
 *   <li>If a store has vanished, release the scope: {@code Stale}. If the destination took all of it, commit the scope into
 *       the caller's transaction (provisional until the outermost commit) and register the receipt's feedback for that
 *       commit. If it took less, release the scope and try once more for what it took; if it took nothing, release and
 *       refuse.</li>
 * </ol>
 */
public final class StorageAccess {
    private StorageAccess() {
    }

    public static MoveOutcome move(StoragePort port, Move move, FlowTransaction tx, MoveFeedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (move.amount() <= 0) {
            return new MoveOutcome.Rejected(Reason.ZERO_REQUEST);
        }
        if (move.from().sameStoreAs(move.to())) {
            return new MoveOutcome.Rejected(Reason.SAME_STORE);
        }
        Goods goods = move.goods();
        if (!port.kinds().contains(goods.kind())) {
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
        long planned = Math.min(move.amount(), Math.min(available, room));

        for (int attempt = 0; attempt < 2; attempt++) {
            FlowTransaction scope = tx.openNested();
            long extracted = source.extract(goods, planned, scope);
            StoreAddress gone = vanished(source, destination);
            if (gone != null) {
                scope.abort();
                return new MoveOutcome.Stale(gone);
            }
            if (extracted <= 0 || extracted > planned) {
                scope.abort();
                return new MoveOutcome.Rejected(Reason.EXTRACTION_FAILED);
            }
            long inserted = destination.insert(goods, extracted, scope);
            gone = vanished(source, destination);
            if (gone != null) {
                scope.abort();
                return new MoveOutcome.Stale(gone);
            }
            if (inserted == extracted) {
                scope.commit();
                MoveReceipt receipt = new MoveReceipt(move.from(), move.to(), goods, move.amount(), inserted);
                tx.onOuterCommit(() -> feedback.moved(receipt));
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
