package mycelium.state_lifecycle.v1.model;

/** A crossing aimed at a host incarnation that is no longer the host's current one. Nothing of it is applied. */
public final class StaleHostException extends CrossingException {
    private static final long serialVersionUID = 1L;

    private final HostRef ref;

    public StaleHostException(HostRef ref, String detail) {
        super(ref + " is not current: " + detail);
        this.ref = ref;
    }

    public HostRef ref() {
        return ref;
    }
}
