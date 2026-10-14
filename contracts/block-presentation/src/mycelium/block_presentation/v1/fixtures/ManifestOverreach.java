package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.Assembler;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.Part;
import mycelium.block_presentation.v1.model.ReferenceMosaic;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Expands every part's template over every value of the inputs it reads, ignoring whether its guard ever holds there (M4). */
public final class ManifestOverreach extends ReferenceMosaic {
    @Override
    protected List<String> manifest(Mosaic pruned) {
        TreeSet<String> models = new TreeSet<>();
        for (int n = 0; n < pruned.parts().size(); n++) {
            Part part = pruned.parts().get(n);
            for (Map<String, Object> tuple : Evaluator.tuples(pruned.inputsOf(n))) {
                models.add(Template.expand(part.model(), tuple));
            }
        }
        return new ArrayList<>(models);
    }
}
