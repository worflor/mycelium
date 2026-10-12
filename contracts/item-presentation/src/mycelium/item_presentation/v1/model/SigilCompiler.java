package mycelium.item_presentation.v1.model;

/** Compiles a program for one target. {@link ReferenceSigil} keeps every law; the rejection matrix holds ones that do not. */
public interface SigilCompiler {
    /**
     * @param program the program, as parsed (this may be refused again: a compiler trusts nothing it was handed)
     * @param lock    the consumer's committed codes, for every item
     * @param catalog the components the program may read
     * @param target  the Minecraft version, and the realization the era table gives it
     */
    Compilation compile(Program program, Lock lock, ComponentCatalog catalog, Target target);

    /** The most tuples a program's read inputs may span before it is refused (law S7). */
    int maxTuples();
}
