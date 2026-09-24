package mycelium.dimension_travel.v2.model;

import java.util.Objects;
import java.util.Set;

/** Reference executor for dimension.travel@2. */
public final class Travel {
    public static final Set<Trait> REQUIRED_TRAITS = Set.of(Trait.POSITION_EXACT, Trait.INVENTORY_PRESERVING);

    private Travel() {
    }

    /**
     * Moves {@code player} to {@code target}. A receipt is produced only once arrival is confirmed
     * ({@code port.exists(player, target.dimension())} and {@code port.where(player).equals(target)}); until
     * then no feedback fires (deferred to the outermost commit). If {@code move} throws {@link PortFailure},
     * the outcome is {@link TravelOutcome.Indeterminate}, never collapsed into {@code Rejected}. If arrival
     * cannot be confirmed after a non-throwing {@code move} (e.g. the target chunk was not actually ready),
     * this call's own transaction is aborted (undoing whatever {@code move} did register) and the outcome is
     * {@code Rejected(TARGET_UNLOADED)}: a rejection leaves no state change behind, same as every other
     * Mycelium contract.
     */
    public static TravelOutcome execute(TravelPort port, PlayerId player, Position target, Pose pose,
                                        TravelTx tx, Feedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(feedback, "feedback");

        if (!port.traits().containsAll(REQUIRED_TRAITS)) {
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

        if (!port.exists(player, target.dimension()) || !port.where(player).equals(target)) {
            tx.abort();
            return new TravelOutcome.Rejected(Reason.TARGET_UNLOADED);
        }

        TravelReceipt receipt = new TravelReceipt(player, source, target, pose);
        tx.onOuterCommit(() -> feedback.traveled(receipt));
        return new TravelOutcome.Committed(receipt);
    }

    /** Same-coordinate travel: the target is {@code where(player)} with only the dimension replaced. */
    public static TravelOutcome drift(TravelPort port, PlayerId player, DimensionId targetDimension, Pose pose,
                                      TravelTx tx, Feedback feedback) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetDimension, "targetDimension");

        Position current = port.where(player);
        Position target = new Position(targetDimension, current.x(), current.y(), current.z(),
                current.yaw(), current.pitch());
        return execute(port, player, target, pose, tx, feedback);
    }
}
