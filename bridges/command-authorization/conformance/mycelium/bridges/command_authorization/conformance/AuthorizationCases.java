package mycelium.bridges.command_authorization.conformance;

import mycelium.command_authorization.v1.model.AuthorizationPort;
import net.minecraft.commands.CommandSourceStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Real-game conformance cases for explicit decisions and source-specific fallbacks through the port. */
public final class AuthorizationCases {
    private AuthorizationCases() {
    }

    public record Case(String name, Runnable body) {
        public void run() {
            body.run();
        }
    }

    /**
     * The caller arranges three test nodes on {@code source}: one explicitly granted, one denied, and one unset.
     * Cases call only {@link AuthorizationPort}, so the same list applies to a bridge or a consumer override.
     */
    public static List<Case> cases(AuthorizationPort<CommandSourceStack> port, CommandSourceStack source,
                                   String grantedNode, String deniedNode, String unsetNode) {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(source, "source");
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("grant_overrides_fallback", () -> expect(
                port.allowed(source, grantedNode, false) && port.allowed(source, grantedNode, true),
                "an explicit grant must override both fallback values")));
        cases.add(new Case("deny_overrides_fallback", () -> expect(
                !port.allowed(source, deniedNode, false) && !port.allowed(source, deniedNode, true),
                "an explicit denial must override both fallback values")));
        cases.add(new Case("unset_uses_effective_fallback", () -> expect(
                !port.allowed(source, unsetNode, false) && port.allowed(source, unsetNode, true),
                "an unset node must follow the source's effective fallback")));
        return List.copyOf(cases);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("command authorization conformance: " + message);
        }
    }
}
