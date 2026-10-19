package mycelium.command_authorization.v1.model;

import java.util.Objects;

/** Resolves explicit command permission decisions against a caller-provided default privilege. */
public final class CommandAuthorization {
    private CommandAuthorization() {
    }

    public enum Decision {
        GRANT,
        DENY,
        UNSET
    }

    public static boolean allowed(Decision decision, boolean fallbackPrivilege) {
        return switch (Objects.requireNonNull(decision, "decision")) {
            case GRANT -> true;
            case DENY -> false;
            case UNSET -> fallbackPrivilege;
        };
    }
}
