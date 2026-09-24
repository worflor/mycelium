package mycelium.dimension_travel.v2.model;

@FunctionalInterface
public interface TravelExecutor {
    TravelOutcome execute(TravelPort port, PlayerId player, Position target, Pose pose, TravelTx tx,
                          Feedback feedback);
}
