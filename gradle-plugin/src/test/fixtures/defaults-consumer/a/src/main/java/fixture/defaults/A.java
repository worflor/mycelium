package fixture.defaults;

import fixture.defaults.mycelium.random_seeded.v1.model.SeededRandom;

public final class A {
    public static String name() {
        return SeededRandom.class.getSimpleName();
    }
}
