package mycelium.bridges.command_authorization.conformance;

import mycelium.command_authorization.v1.model.AuthorizationPort;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** Variant rig for obtaining the live command source whose permission decisions the cases exercise. */
public final class CommandSourceWire {
    private CommandSourceWire() {
    }

    public static CommandSourceStack playerSource(ServerPlayer player) {
        return player.createCommandSourceStack();
    }

    public static List<AuthorizationCases.Case> playerCases(AuthorizationPort<CommandSourceStack> port,
                                                              ServerPlayer player,
                                                              String grantedNode,
                                                              String deniedNode,
                                                              String unsetNode) {
        return AuthorizationCases.cases(port, playerSource(player), grantedNode, deniedNode, unsetNode);
    }
}
