package fixture.flex.kernel;

/** A pure class that reads the clock: P3, a warning unless the mod says otherwise. */
public final class Clock {
    public static long now() {
        return System.currentTimeMillis();
    }
}
