/** A plain Java 17 source used to prove the daemon JDK can compile the default pure release. */
public final class DefaultReleaseProbe {
    private DefaultReleaseProbe() {
    }

    public static int value() {
        return 42;
    }
}
