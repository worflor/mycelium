package mycelium.dimension_travel.v2.model;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Compatibility behavior is defined by this contract and its SPEC. */
public final class Trip {
    public static final Set<Trait> REQUIRED_TRAITS = Set.of(Trait.POSITION_EXACT, Trait.INVENTORY_PRESERVING, Trait.JOURNALED);

    public static final TripExecutor REFERENCE = new TripExecutor() {
        @Override
        public TravelOutcome depart(TravelPort port, PlayerId player, Position target, Pose pose, TravelTx tx, Feedback feedback) {
            return Trip.depart(port, player, target, pose, tx, feedback);
        }

        @Override
        public TravelOutcome returnHome(TravelPort port, PlayerId player, Pose pose, TravelTx tx, Feedback feedback) {
            return Trip.returnHome(port, player, pose, tx, feedback);
        }

        @Override
        public RecoverOutcome recover(TravelPort port, PlayerId player, TravelTx tx, Settle settle) {
            return Trip.recover(port, player, tx, settle);
        }
    };

    private Trip() {
    }

    /**
     * Leave for {@code target}. Check order: traits, an open trip ({@code TRIP_OPEN}), presence, same position.
     * The journal is written (under a nested transaction) before {@code move}; a rejected arrival aborts it with
     * the move, and a failed move ({@code Indeterminate}) keeps it, because that is exactly the case it exists for.
     */
    public static TravelOutcome depart(TravelPort port, PlayerId player, Position target, Pose pose, TravelTx tx, Feedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(tx, "tx");
        if (!port.traits().containsAll(REQUIRED_TRAITS)) {
            return new TravelOutcome.Unsupported("port lacks required traits");
        }
        if (port.journal(player).isPresent()) {
            return new TravelOutcome.Rejected(Reason.TRIP_OPEN);
        }
        Position origin = port.where(player);
        if (!port.exists(player, origin.dimension())) {
            return new TravelOutcome.Rejected(Reason.NOT_FOUND);
        }
        if (target.equals(origin)) {
            return new TravelOutcome.Rejected(Reason.SAME_POSITION);
        }
        TravelTx inner = tx.openNested();
        port.writeJournal(player, new Journal(player, origin, port.inventory(player)), inner);
        TravelOutcome outcome = Travel.execute(port, player, target, pose, inner, feedback);
        if (inner.state() == TravelTx.State.OPEN) {
            if (outcome instanceof TravelOutcome.Committed || outcome instanceof TravelOutcome.Indeterminate) {
                inner.commit();
            } else {
                inner.abort();
            }
        }
        return outcome;
    }

    /** Come home: back to the journal's origin; the journal is struck with the move, in the same transaction. */
    public static TravelOutcome returnHome(TravelPort port, PlayerId player, Pose pose, TravelTx tx, Feedback feedback) {
        Objects.requireNonNull(port, "port");
        if (!port.traits().containsAll(REQUIRED_TRAITS)) {
            return new TravelOutcome.Unsupported("port lacks required traits");
        }
        Optional<Journal> open = port.journal(player);
        if (open.isEmpty()) {
            return new TravelOutcome.Rejected(Reason.NO_TRIP);
        }
        TravelTx inner = tx.openNested();
        TravelOutcome outcome = Travel.execute(port, player, open.get().origin(), pose, inner, feedback);
        if (outcome instanceof TravelOutcome.Committed) {
            port.strikeJournal(player, inner);
            inner.commit();
        } else if (inner.state() == TravelTx.State.OPEN) {
            inner.abort();
        }
        return outcome;
    }

    /**
     * At the start of a session: an open journal means the process died mid-trip. Bring the traveller home if they
     * are not, strike the journal, and ask the consumer to settle exactly once, after the outermost commit. Already
     * home (the process died after the move but before the strike): strike only; there is nothing to settle.
     */
    public static RecoverOutcome recover(TravelPort port, PlayerId player, TravelTx tx, Settle settle) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(settle, "settle");
        if (!port.traits().containsAll(REQUIRED_TRAITS)) {
            return new RecoverOutcome.Unsupported("port lacks required traits");
        }
        Optional<Journal> open = port.journal(player);
        if (open.isEmpty()) {
            return new RecoverOutcome.Rejected(Reason.NO_TRIP);
        }
        Journal journal = open.get();
        TravelTx inner = tx.openNested();
        boolean away = !port.where(player).equals(journal.origin());
        if (away) {
            TravelOutcome back = Travel.execute(port, player, journal.origin(), Pose.STANDING, inner, r -> { });
            if (!(back instanceof TravelOutcome.Committed)) {
                if (inner.state() == TravelTx.State.OPEN) {
                    inner.abort();
                }
                return new RecoverOutcome.Indeterminate("the way home failed: " + back);
            }
            Inventory now = port.inventory(player);
            tx.onOuterCommit(() -> settle.settle(journal, now));
        }
        port.strikeJournal(player, inner);
        inner.commit();
        return new RecoverOutcome.Recovered(new RecoverReceipt(player, journal, away));
    }
}
