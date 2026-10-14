package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.BlockRealization;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.MosaicPlan;
import mycelium.block_presentation.v1.model.ReferenceMosaic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Hands the adapter the parts last-declared first, so a part meant to paint over another is painted over by it (M2). */
public final class SwappedOrder extends ReferenceMosaic {
    @Override
    protected MosaicPlan newPlan(Mosaic pruned, BlockRealization realization, List<String> manifest) {
        return new WarpedPlan(super.newPlan(pruned, realization, manifest), placed -> {
            List<mycelium.block_presentation.v1.model.Placed> out = new ArrayList<>(placed);
            Collections.reverse(out);
            return out;
        });
    }
}
