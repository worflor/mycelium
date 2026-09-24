package mycelium.dimension_travel.v1.fixtures;

import mycelium.dimension_travel.v1.model.Feedback;
import mycelium.dimension_travel.v1.model.PlayerId;
import mycelium.dimension_travel.v1.model.PortFailure;
import mycelium.dimension_travel.v1.model.Position;
import mycelium.dimension_travel.v1.model.Pose;
import mycelium.dimension_travel.v1.model.Reason;
import mycelium.dimension_travel.v1.model.Travel;
import mycelium.dimension_travel.v1.model.TravelOutcome;
import mycelium.dimension_travel.v1.model.TravelPort;
import mycelium.dimension_travel.v1.model.TravelReceipt;
import mycelium.dimension_travel.v1.model.TravelTx;

/** Emits the receipt and feedback right after calling move, without confirming arrival: violates L4. */
public final class PrematureTravelReceiptExecutor {
    private PrematureTravelReceiptExecutor() {
    }

    public static TravelOutcome execute(TravelPort port, PlayerId player, Position target, Pose pose,
                                        TravelTx tx, Feedback feedback) {
        if (!port.traits().containsAll(Travel.REQUIRED_TRAITS)) {
            return new TravelOutcome.Unsupported("port lacks required traits");
        }
        Position source = port.where(player);
        if (!port.exists(player, source.dimension())) {
            return new TravelOutcome.Rejected(Reason.NOT_FOUND);
        }
        if (target.equals(source)) {
            return new TravelOutcome.Rejected(Reason.SAME_POSITION);
        }
        try {
            port.move(player, target, pose, tx);
        } catch (PortFailure e) {
            return new TravelOutcome.Indeterminate(e.getMessage() == null ? "move failed" : e.getMessage());
        }
        // BUG: no arrival verification before producing a receipt and firing feedback immediately.
        TravelReceipt receipt = new TravelReceipt(player, source, target, pose);
        feedback.traveled(receipt);
        return new TravelOutcome.Committed(receipt);
    }
}
