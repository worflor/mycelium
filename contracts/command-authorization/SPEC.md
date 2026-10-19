# command.authorization@1

Deciding whether a command may run when a command source can return an explicit grant, an explicit denial, or no
decision. Written for WorldPrompt after its command checks were found to need a loader-neutral meaning: explicit
permission nodes must override the operator-based default in either direction.

## Model

`Decision` is the result of querying a named permission node: `GRANT`, `DENY`, or `UNSET`. `CommandAuthorization.allowed`
takes that result and the caller's fallback privilege and returns whether the command is authorized. `AuthorizationPort<S>`
is the platform seam: a bridge implements `allowed(source, node, fallbackPrivilege)` and supplies the queried decision to
the pure rule.

The fallback is supplied by the native adapter. It represents the command's existing default policy, such as an
operator/moderator check. The model does not know about command sources, nodes, loaders, or Minecraft.

## Laws

| Law | Statement |
|---|---|
| A1 | `GRANT` authorizes execution regardless of fallback privilege. |
| A2 | `DENY` refuses execution regardless of fallback privilege, including for a privileged source. |
| A3 | `UNSET` authorizes exactly when the fallback privilege is true. |

These laws define authorization only. The command implementation remains responsible for its positive execution
obligations, validation, and effects once authorized.

## Rejection matrix

| Broken fixture | What it gets wrong | Caught by |
|---|---|---|
| `GrantRequiresFallback` | Treats an explicit grant as insufficient for a non-privileged source | A1:grant-is-authoritative |
| `DenyAllowsPrivileged` | Lets the fallback privilege override an explicit denial | A2:deny-is-authoritative |
| `UnsetAlwaysAllows` | Treats an unset permission as a grant | A3:unset-uses-fallback |
| `NoOpAuthorization` | Refuses every command, including explicit grants | A1:grant-is-authoritative |

## Using it

The native bridge implements `AuthorizationPort<CommandSourceStack>` without depending on a loader. Fabric maps its
tri-state query directly to `Decision`. NeoForge 26.3 registers familiar Boolean permission nodes with the invocation's
fallback privilege as a dynamic context: the default resolver returns that value, and a permission handler may override
it. A permission API caller that omits the dynamic context still gets the node's declared policy: open nodes allow, and
moderator-only nodes check an online player's effective moderator permission. Offline checks of moderator-only nodes
refuse because moderator status cannot be established. The adapter maps the resolved Boolean to `GRANT` or `DENY`; for
non-player sources, which NeoForge's API cannot query, it returns `UNSET` and the port applies the supplied fallback.
Create the NeoForge adapter and bind it as the query before commands can be parsed during datapack loading, then register
that same adapter's nodes in `PermissionGatherEvent.Nodes`. Its nodes are immutable after creation and may be added on
each server lifecycle event. A command with an open default passes true; a moderator-only default passes the source's
effective moderator/operator privilege. Keep any dynamic check, such as requiring undo permission to cancel an active
rebuild, in the command's existing execution path and supply that privilege as the fallback for that check.

The model does not register permission nodes or establish that a loader's permission API behaves as expected. Native
adapter tests and game tests establish those facts. A consumer's NeoForge game tests should also query registered nodes
without dynamic contexts to hold the declared defaults for online and offline sources.

## Running the harness

From the Mycelium root, `bash run-contracts.sh command-authorization` (or `.\run-contracts.ps1 command-authorization`),
or `./gradlew myceliumVerify -Pmycelium.contracts=command.authorization@1`. Expected: the reference clean, every matrix
entry `CAUGHT`, exit code 0.
