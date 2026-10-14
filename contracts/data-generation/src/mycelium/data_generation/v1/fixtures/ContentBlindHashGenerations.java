package mycelium.data_generation.v1.fixtures;

import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Hashing;
import mycelium.data_generation.v1.model.Part;

/**
 * Deliberately incorrect: fingerprints an entry by its id, not its content, so a datapack that retunes a number
 * keeps the base generation's name.
 */
public final class ContentBlindHashGenerations<D, G> extends Pipeline<D, G> {
    public ContentBlindHashGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected String fingerprint(Part part) {
        return Hashing.fingerprint(part.ref().toString()); // BUG
    }
}
