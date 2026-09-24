package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Feedback;
import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.Pose;
import mycelium.dimension_travel.v2.model.Position;
import mycelium.dimension_travel.v2.model.RecoverOutcome;
import mycelium.dimension_travel.v2.model.RecoverReceipt;
import mycelium.dimension_travel.v2.model.Settle;
import mycelium.dimension_travel.v2.model.Travel;
import mycelium.dimension_travel.v2.model.TravelOutcome;
import mycelium.dimension_travel.v2.model.TravelPort;
import mycelium.dimension_travel.v2.model.TravelTx;
import mycelium.dimension_travel.v2.model.Trip;
import mycelium.dimension_travel.v2.model.TripExecutor;

/** Deliberately incorrect: settles at once, so an aborted recovery has already changed the consumer's ledger (L7). */
public final class EagerSettleExecutor implements TripExecutor {
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
        Journal j = port.journal(player).orElse(null);
        if (j == null) {
            return new RecoverOutcome.Rejected(mycelium.dimension_travel.v2.model.Reason.NO_TRIP);
        }
        Travel.execute(port, player, j.origin(), Pose.STANDING, tx, r -> { });
        settle.settle(j, port.inventory(player));
        port.strikeJournal(player, tx);
        return new RecoverOutcome.Recovered(new RecoverReceipt(player, j, true));
    }
}
