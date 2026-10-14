package mycelium.block_presentation.v1.model;

import mycelium.item_presentation.v1.model.ComponentCatalog;

/** Compiles a mosaic for one target. {@link ReferenceMosaic} keeps every law; the rejection matrix holds ones that do not. */
public interface MosaicCompiler {
    /**
     * @param mosaic  the mosaic, as parsed (this may be refused again: a compiler trusts nothing it was handed)
     * @param catalog the components the mosaic may read
     * @param target  the Minecraft version, and the realization the era table gives it
     */
    MosaicCompilation compile(Mosaic mosaic, ComponentCatalog catalog, BlockTarget target);

    /** The most tuples any one part of a mosaic may span before it is refused (law M7). */
    int maxSpan();
}
