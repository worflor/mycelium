package mycelium.command_authorization.v1.harness;

import mycelium.command_authorization.v1.fixtures.AuthorizationPolicy;
import mycelium.command_authorization.v1.model.CommandAuthorization;
import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;
import mycelium.harness.Check;

/** command.authorization@1: explicit command decisions and the default privilege (SPEC.md). */
public final class CommandAuthorizationContractTests {
    private final AuthorizationPolicy policy;

    public CommandAuthorizationContractTests(AuthorizationPolicy policy) {
        this.policy = policy;
    }

    public static Check reference() {
        return new CommandAuthorizationContractTests(CommandAuthorization::allowed).run();
    }

    public Check run() {
        Check c = new Check();
        c.that(policy.allowed(Decision.GRANT, false) && policy.allowed(Decision.GRANT, true),
                "A1:grant-is-authoritative");
        c.that(!policy.allowed(Decision.DENY, false) && !policy.allowed(Decision.DENY, true),
                "A2:deny-is-authoritative");
        c.that(!policy.allowed(Decision.UNSET, false) && policy.allowed(Decision.UNSET, true),
                "A3:unset-uses-fallback");
        return c;
    }
}
