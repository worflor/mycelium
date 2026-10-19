package mycelium.command_authorization.v1.fixtures;

import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;

public final class AuthorizationVariants {
    private AuthorizationVariants() {
    }

    public static boolean grantRequiresFallback(Decision decision, boolean fallbackPrivilege) {
        return decision == Decision.GRANT ? fallbackPrivilege
                : decision == Decision.DENY ? false : fallbackPrivilege;
    }

    public static boolean denyAllowsPrivileged(Decision decision, boolean fallbackPrivilege) {
        return decision == Decision.GRANT || fallbackPrivilege;
    }

    public static boolean unsetAlwaysAllows(Decision decision, boolean fallbackPrivilege) {
        return decision != Decision.DENY;
    }

    public static boolean noOp(Decision decision, boolean fallbackPrivilege) {
        return false;
    }
}
