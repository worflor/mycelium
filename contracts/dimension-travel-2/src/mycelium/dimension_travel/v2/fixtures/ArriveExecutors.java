package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Feedback;
import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.Pose;
import mycelium.dimension_travel.v2.model.Position;
import mycelium.dimension_travel.v2.model.Reason;
import mycelium.dimension_travel.v2.model.RecoverOutcome;
import mycelium.dimension_travel.v2.model.RecoverReceipt;
import mycelium.dimension_travel.v2.model.Settle;
import mycelium.dimension_travel.v2.model.Travel;
import mycelium.dimension_travel.v2.model.TravelOutcome;
import mycelium.dimension_travel.v2.model.TravelPort;
import mycelium.dimension_travel.v2.model.TravelTx;
import mycelium.dimension_travel.v2.model.Trip;
import mycelium.dimension_travel.v2.model.TripExecutor;

import java.util.Optional;

/** Deliberately incorrect executors whose one-way arrival (L9) is wrong; every other verb is the reference. */
public final class ArriveExecutors {
    private ArriveExecutors() {
    }

    private abstract static class Reference implements TripExecutor {
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
    }

    /** Says the trip has ended and leaves the journal open. */
    public static TripExecutor keepsJournal() {
        return new Reference() {
            @Override
            public RecoverOutcome arrive(TravelPort port, PlayerId player, TravelTx tx) {
                Optional<Journal> open = port.journal(player);
                if (open.isEmpty()) {
                    return new RecoverOutcome.Rejected(Reason.NO_TRIP);
                }
                return new RecoverOutcome.Recovered(new RecoverReceipt(player, open.get(), false));
            }
        };
    }

    /** Ends the trip by bringing the traveller back to where it began. */
    public static TripExecutor movesHome() {
        return new Reference() {
            @Override
            public RecoverOutcome arrive(TravelPort port, PlayerId player, TravelTx tx) {
                Optional<Journal> open = port.journal(player);
                if (open.isEmpty()) {
                    return new RecoverOutcome.Rejected(Reason.NO_TRIP);
                }
                Travel.execute(port, player, open.get().origin(), Pose.STANDING, tx, r -> { });
                port.strikeJournal(player, tx);
                return new RecoverOutcome.Recovered(new RecoverReceipt(player, open.get(), true));
            }
        };
    }

    /** Strikes the journal in a transaction of its own, so an abort above it cannot bring the trip back. */
    public static TripExecutor ignoresAbort() {
        return new Reference() {
            @Override
            public RecoverOutcome arrive(TravelPort port, PlayerId player, TravelTx tx) {
                Optional<Journal> open = port.journal(player);
                if (open.isEmpty()) {
                    return new RecoverOutcome.Rejected(Reason.NO_TRIP);
                }
                TravelTx own = TravelTx.open();
                port.strikeJournal(player, own);
                own.commit();
                return new RecoverOutcome.Recovered(new RecoverReceipt(player, open.get(), false));
            }
        };
    }
}
