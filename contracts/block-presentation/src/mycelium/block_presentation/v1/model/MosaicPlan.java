package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentView;

import java.util.List;
import java.util.Map;

/**
 * What a compilation hands a platform adapter, and what the adapter evaluates at run time: the mosaic's read inputs and parts and the
 * manifest, the models its client must have loaded before any block can be drawn. The plan resource is the build's output; the
 * models themselves are the consumer's assets.
 */
public interface MosaicPlan {
    String block();

    BlockRealization realization();

    /** The mosaic with the inputs no part reads dropped. */
    Mosaic mosaic();

    /** Every model any state can draw, sorted: an adapter loads exactly these, and a state never draws one that is not here. */
    List<String> manifest();

    /** The parts a block with this data draws, in drawing order. Total: absent data reads as the defaults. */
    Assembly assemble(ComponentView view);

    /** The parts these input values draw (a tuple that covers the mosaic's read inputs). */
    List<Placed> assemble(Map<String, Object> tuple);

    /** The plan as a tree of JSON values, the form the build writes as a resource. */
    Map<String, Object> toTree();
}
