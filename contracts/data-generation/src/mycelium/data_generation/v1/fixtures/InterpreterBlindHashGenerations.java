package mycelium.data_generation.v1.fixtures;

import mycelium.data_catalog.v1.model.Snapshot;
import mycelium.data_generation.v1.model.Assembler;
import mycelium.data_generation.v1.model.Hashing;
import mycelium.data_generation.v1.model.Part;
import mycelium.data_generation.v1.model.Ref;

import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * Deliberately incorrect: leaves the interpreter version out of the hash, so a record whose meaning the code
 * changed keeps its old name, and a record stamped with the hash cannot say which code ran it.
 */
public final class InterpreterBlindHashGenerations<D, G> extends Pipeline<D, G> {
    public InterpreterBlindHashGenerations(Assembler<D, G> assembler) {
        super(assembler);
    }

    @Override
    protected String hash(String interpreter, SortedMap<Ref, String> manifest, List<Part> listed, Map<String, ? extends Snapshot<?>> offered) {
        return Hashing.generation("", manifest); // BUG
    }
}
