package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.BlockRealization;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.MosaicPlan;
import mycelium.block_presentation.v1.model.Placed;
import mycelium.block_presentation.v1.model.ReferenceMosaic;

import java.util.ArrayList;
import java.util.List;

/** Hands the adapter every turn the wrong way round (a quarter-turn clockwise drawn anticlockwise) (M2). */
public final class ReversedTurn extends ReferenceMosaic {
    @Override
    protected MosaicPlan newPlan(Mosaic pruned, BlockRealization realization, List<String> manifest) {
        return new WarpedPlan(super.newPlan(pruned, realization, manifest), placed -> {
            List<Placed> out = new ArrayList<>();
            for (Placed p : placed) {
                out.add(new Placed(p.model(), (360 - p.turn()) % 360, p.part()));
            }
            return out;
        });
    }
}
