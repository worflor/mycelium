package mycelium.bridges.command_authorization;

import mycelium.command_authorization.v1.model.CommandAuthorization.Decision;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContext;
import net.neoforged.neoforge.server.permission.nodes.PermissionDynamicContextKey;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** NeoForge 26.3 boolean-node adapter, retaining the invocation's effective fallback in a dynamic context. */
public final class NeoForgePermissions implements CommandPermissions.DecisionQuery {
    public static final PermissionDynamicContextKey<Boolean> FALLBACK = new PermissionDynamicContextKey<>(
            Boolean.class, "mycelium.command_authorization.fallback", String::valueOf);

    private final Map<String, PermissionNode<Boolean>> nodes;

    private NeoForgePermissions(Map<String, PermissionNode<Boolean>> nodes) {
        this.nodes = Map.copyOf(nodes);
    }

    /**
     * Creates the mod-owned nodes and their context-free policy before they are registered. Each live query may supply
     * its effective fallback as a dynamic context; permission handlers and admin tools that omit it still get the
     * declared node policy. A true map value means the node requires moderator permission by default; false means it
     * is open by default.
     */
    public static NeoForgePermissions create(Map<String, Boolean> operatorDefaults) {
        Objects.requireNonNull(operatorDefaults, "operatorDefaults");
        Map<String, PermissionNode<Boolean>> nodes = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : operatorDefaults.entrySet()) {
            String nodeName = entry.getKey();
            boolean operatorDefault = Objects.requireNonNull(entry.getValue(), "operatorDefault for " + nodeName);
            String[] parts = splitNode(nodeName);
            PermissionNode<Boolean> node = new PermissionNode<>(parts[0], parts[1], PermissionTypes.BOOLEAN,
                    (player, playerId, contexts) -> fallbackFrom(player, operatorDefault, contexts),
                    FALLBACK);
            if (nodes.putIfAbsent(nodeName, node) != null) {
                throw new IllegalArgumentException("duplicate command permission node: " + nodeName);
            }
        }
        return new NeoForgePermissions(nodes);
    }

    /** Registers the nodes owned by this adapter on a native permission-gather event. */
    public void register(PermissionGatherEvent.Nodes event) {
        Objects.requireNonNull(event, "event");
        nodes.values().forEach(node -> event.addNodes(node));
    }

    /** Creates and registers an adapter in one call for consumers that do not need early query access. */
    public static NeoForgePermissions register(PermissionGatherEvent.Nodes event,
                                               Map<String, Boolean> operatorDefaults) {
        Objects.requireNonNull(event, "event");
        NeoForgePermissions permissions = create(operatorDefaults);
        permissions.register(event);
        return permissions;
    }

    @Override
    public Decision decision(CommandSourceStack source, String nodeName, boolean fallbackPrivilege) {
        PermissionNode<Boolean> node = nodes.get(Objects.requireNonNull(nodeName, "nodeName"));
        if (node == null) {
            throw new IllegalArgumentException("unregistered command permission node: " + nodeName);
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            // NeoForge 26.3 PermissionAPI accepts ServerPlayer, not CommandSourceStack.
            return Decision.UNSET;
        }
        boolean granted = PermissionAPI.getPermission(player, node, FALLBACK.createContext(fallbackPrivilege));
        return granted ? Decision.GRANT : Decision.DENY;
    }

    private static boolean fallbackFrom(ServerPlayer player, boolean operatorDefault,
                                        PermissionDynamicContext<?>... contexts) {
        for (PermissionDynamicContext<?> context : contexts) {
            if (FALLBACK.equals(context.getDynamic())) {
                return (Boolean) context.getValue();
            }
        }
        // Without a source-specific context, use the declared policy. Offline checks cannot prove moderator status.
        return !operatorDefault || (player != null
                && player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_MODERATOR));
    }

    private static String[] splitNode(String nodeName) {
        Objects.requireNonNull(nodeName, "nodeName");
        int separator = nodeName.indexOf('.');
        if (separator <= 0 || separator == nodeName.length() - 1) {
            throw new IllegalArgumentException("permission node must be <modid>.<path>: " + nodeName);
        }
        return new String[]{nodeName.substring(0, separator), nodeName.substring(separator + 1)};
    }
}
