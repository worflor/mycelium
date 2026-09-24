package mycelium.dimension_travel.v2.fixtures;

import mycelium.dimension_travel.v2.model.Feedback;
import mycelium.dimension_travel.v2.model.Journal;
import mycelium.dimension_travel.v2.model.PlayerId;
import mycelium.dimension_travel.v2.model.Pose;
import mycelium.dimension_travel.v2.model.Position;
import mycelium.dimension_travel.v2.model.RecoverOutcome;
import mycelium.dimension_travel.v2.model.Settle;
import mycelium.dimension_travel.v2.model.Travel;
import mycelium.dimension_travel.v2.model.TravelOutcome;
import mycelium.dimension_travel.v2.model.TravelPort;
import mycelium.dimension_travel.v2.model.TravelTx;
import mycelium.dimension_travel.v2.model.Trip;
import mycelium.dimension_travel.v2.model.TripExecutor;

/** Deliberately incorrect: departs over an open trip, overwriting where home is (L2). */
public final class OverwritingDepartExecutor implements TripExecutor {
    @Override
    public TravelOutcome depart(TravelPort port, PlayerId player, Position target, Pose pose, TravelTx tx, Feedback feedback) {
        port.writeJournal(player, new Journal(player, port.where(player), port.inventory(player)), tx);
        return Travel.execute(port, player, target, pose, tx, feedback);
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
