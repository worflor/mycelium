package mycelium.command_authorization.v1.model;

/** The platform seam for deciding whether a command is authorized. */
@FunctionalInterface
public interface AuthorizationPort<S> {
    /**
     * Returns whether {@code source} may use {@code node}; the fallback represents the command's normal default
     * privilege when the platform has no explicit decision.
     */
    boolean allowed(S source, String node, boolean fallbackPrivilege);
}
