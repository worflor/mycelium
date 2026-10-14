package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.Assembly;
import mycelium.block_presentation.v1.model.BlockRealization;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.MosaicPlan;
import mycelium.block_presentation.v1.model.Placed;
import mycelium.item_presentation.v1.model.ComponentView;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * A plan that is the reference plan except in what it hands an adapter at run time: the parts it draws pass through {@code warp}.
 * Its resource is the reference's, so only an adapter reading the live plan sees the fault.
 */
final class WarpedPlan implements MosaicPlan {
    private final MosaicPlan plan;
    private final UnaryOperator<List<Placed>> warp;

    WarpedPlan(MosaicPlan plan, UnaryOperator<List<Placed>> warp) {
        this.plan = plan;
        this.warp = warp;
    }

    @Override
    public String block() {
        return plan.block();
    }

    @Override
    public BlockRealization realization() {
        return plan.realization();
    }

    @Override
    public Mosaic mosaic() {
        return plan.mosaic();
    }

    @Override
    public List<String> manifest() {
        return plan.manifest();
    }

    @Override
    public Assembly assemble(ComponentView view) {
        Assembly a = plan.assemble(view);
        return new Assembly(warp.apply(a.placed()), a.inDomain());
    }

    @Override
    public List<Placed> assemble(Map<String, Object> tuple) {
        return warp.apply(plan.assemble(tuple));
    }

    @Override
    public Map<String, Object> toTree() {
        return plan.toTree();
    }
}
