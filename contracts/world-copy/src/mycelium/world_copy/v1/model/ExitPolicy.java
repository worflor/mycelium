package mycelium.world_copy.v1.model;

/**
 * Which tokens are ways out of the copy (portals, gateways) and what stands in their place. A copy is a sandbox; a
 * copied exit can break that isolation (Somnium, 2026-09-23: a copied portal allowed an unintended exit). Added to {@code @1} additively (L8).
 */
public interface ExitPolicy {
    /** Copies everything, exits included (what the plain {@code execute} does). */
    ExitPolicy NONE = new ExitPolicy() {
        @Override
        public boolean isExit(BlockToken token) {
            return false;
        }

        @Override
        public BlockToken replacement(BlockToken exit) {
            return exit;
        }
    };

    boolean isExit(BlockToken token);

    /** What is written where an exit would have been (air, usually). */
    BlockToken replacement(BlockToken exit);
}
