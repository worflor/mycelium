package mycelium.dimension_travel.v2.model;

/** The three verbs of a journaled trip; {@link Trip#REFERENCE} is the reference. */
public interface TripExecutor {
    TravelOutcome depart(TravelPort port, PlayerId player, Position target, Pose pose, TravelTx tx, Feedback feedback);

    TravelOutcome returnHome(TravelPort port, PlayerId player, Pose pose, TravelTx tx, Feedback feedback);

    RecoverOutcome recover(TravelPort port, PlayerId player, TravelTx tx, Settle settle);
}
