package external;

/** Deliberately excluded from analyzer input; runtime observations use this eager scheduler. */
public class Api {
    public static int calls;
    public static void register(Runnable callback) { calls++; callback.run(); }
}
