package mycelium.command_authorization.v1.fixtures;

import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;

@FunctionalInterface
public interface AuthorizationPolicy {
    boolean allowed(Decision decision, boolean fallbackPrivilege);
}
