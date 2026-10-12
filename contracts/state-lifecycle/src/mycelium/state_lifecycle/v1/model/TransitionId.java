package mycelium.state_lifecycle.v1.model;

/**
 * The name of a transition: {@code namespace:path}. The platform's own are the constants below, in the
 * {@code mycelium} namespace, which a consumer may not declare into; a consumer declares its own in its namespace
 * ({@link Transitions.Builder#declare}).
 */
public record TransitionId(String value) implements Comparable<TransitionId> {
    public static final String PLATFORM_NAMESPACE = "mycelium";

    public static final TransitionId SAVE = platform("save");
    public static final TransitionId LOAD = platform("load");
    public static final TransitionId LOGIN = platform("login");
    public static final TransitionId LOGOUT = platform("logout");
    public static final TransitionId DEATH = platform("death");
    public static final TransitionId RESPAWN = platform("respawn");
    public static final TransitionId CLONE = platform("clone");
    public static final TransitionId DIMENSION_CHANGE = platform("dimension_change");
    public static final TransitionId UNLOAD = platform("unload");
    public static final TransitionId REMOVE = platform("remove");
    public static final TransitionId SERVER_STOP = platform("server_stop");

    public TransitionId {
        Ids.check("transition id", value);
    }

    private static TransitionId platform(String path) {
        return new TransitionId(PLATFORM_NAMESPACE + ":" + path);
    }

    public boolean isPlatform() {
        return Ids.namespace(value).equals(PLATFORM_NAMESPACE);
    }

    @Override
    public int compareTo(TransitionId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
