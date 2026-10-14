package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.Assembly;
import mycelium.block_presentation.v1.model.BlockRealization;
import mycelium.block_presentation.v1.model.BlockTarget;
import mycelium.block_presentation.v1.model.Layout;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.MosaicCompilation;
import mycelium.block_presentation.v1.model.MosaicCompiler;
import mycelium.block_presentation.v1.model.MosaicPlan;
import mycelium.block_presentation.v1.model.Placed;
import mycelium.block_presentation.v1.model.ReferenceMosaic;
import mycelium.block_presentation.v1.model.ReferenceMosaicPlan;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.ComponentView;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Json;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Does nothing: every block draws no part at all, whatever its state. Nothing it emits is malformed, and nothing it emits is the mosaic. */
public final class NoOpMosaic implements MosaicCompiler {
    @Override
    public int maxSpan() {
        return Evaluator.DEFAULT_MAX_TUPLES;
    }

    @Override
    public MosaicCompilation compile(Mosaic mosaic, ComponentCatalog catalog, BlockTarget target) {
        if (target.realization() == BlockRealization.NONE) {
            return new MosaicCompilation.Rejected(Collections.singletonList(new Diagnostic(Diagnostic.Kind.NO_REALIZATION, mosaic.block(),
                    "Minecraft " + target.minecraft() + " has nothing to draw with")));
        }
        MosaicPlan plan = new Nothing(mosaic.pruned(), target.realization());
        Map<String, String> files = new TreeMap<>();
        files.put(Layout.plan(mosaic.block()), Json.write(plan.toTree()));
        return new MosaicCompilation.Compiled(plan, files);
    }

    private record Nothing(Mosaic mosaic, BlockRealization realization) implements MosaicPlan {
        @Override
        public String block() {
            return mosaic.block();
        }

        @Override
        public List<String> manifest() {
            return Collections.emptyList();
        }

        @Override
        public Assembly assemble(ComponentView view) {
            return new Assembly(Collections.emptyList(), true);
        }

        @Override
        public List<Placed> assemble(Map<String, Object> tuple) {
            return Collections.emptyList();
        }

        @Override
        public Map<String, Object> toTree() {
            return new ReferenceMosaicPlan(mosaic, realization, Collections.<String>emptyList()).toTree();
        }
    }
}
