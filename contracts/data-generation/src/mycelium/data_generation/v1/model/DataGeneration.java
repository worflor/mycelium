package mycelium.data_generation.v1.model;

/** Entry point: the reference generations over a mod's assembler. */
public final class DataGeneration {
    private DataGeneration() {
    }

    public static <D, G> Generations<G> create(Assembler<D, G> assembler) {
        return new StandardGenerations<>(assembler);
    }
}
