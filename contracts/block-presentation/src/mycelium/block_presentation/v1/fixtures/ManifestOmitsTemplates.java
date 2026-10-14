package mycelium.block_presentation.v1.fixtures;

import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.Part;
import mycelium.block_presentation.v1.model.ReferenceMosaic;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Lists in the manifest only the models parts name outright, forgetting every model a template expands to (M4). */
public final class ManifestOmitsTemplates extends ReferenceMosaic {
    @Override
    protected List<String> manifest(Mosaic pruned) {
        TreeSet<String> models = new TreeSet<>();
        for (Part p : pruned.parts()) {
            if (p.model().indexOf('{') < 0) {
                models.add(p.model());
            }
        }
        return new ArrayList<>(models);
    }
}
