package mycelium.bridges.command_authorization;

import mycelium.command_authorization.v1.model.AuthorizationPort;
import mycelium.command_authorization.v1.model.CommandAuthorization;
import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;
import net.minecraft.commands.CommandSourceStack;

import java.util.Objects;

/** Minecraft 26.3 command-source adapter; loader permission APIs are supplied by the loader overlays. */
public final class CommandPermissions implements AuthorizationPort<CommandSourceStack> {
    private final DecisionQuery query;

    public CommandPermissions(DecisionQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public boolean allowed(CommandSourceStack source, String node, boolean fallbackPrivilege) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(node, "node");
        return CommandAuthorization.allowed(query.decision(source, node, fallbackPrivilege), fallbackPrivilege);
    }

    /** Loader-specific query seam. The fallback is passed for APIs that resolve it inside their permission node. */
    @FunctionalInterface
    public interface DecisionQuery {
        Decision decision(CommandSourceStack source, String node, boolean fallbackPrivilege);
    }
}
