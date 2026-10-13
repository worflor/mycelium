package fixture.flex;

import fixture.flex.mycelium.random_seeded.v1.model.SeededRandom;

/** The mod: it uses a contract (found from this import) and reaches an optional integration only through Gate. */
public final class Mod {
    public static long roll(long seed) {
        return SeededRandom.class.getName().length() + seed + Gate.value();
    }
}
