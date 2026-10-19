package mycelium.bridges.command_authorization;

import me.lucko.fabric.api.permissions.v0.Permissions;
import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;
import net.minecraft.commands.CommandSourceStack;

/** Fabric Permissions API adapter. Its Optional<Boolean> preserves explicit grant, explicit denial and unset. */
public final class FabricPermissions implements CommandPermissions.DecisionQuery {
    @Override
    public Decision decision(CommandSourceStack source, String node, boolean fallbackPrivilege) {
        return Permissions.getPermissionValue(source, node)
                .map(granted -> granted ? Decision.GRANT : Decision.DENY)
                .orElse(Decision.UNSET);
    }
}
