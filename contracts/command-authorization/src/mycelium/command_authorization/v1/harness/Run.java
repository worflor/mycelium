package mycelium.command_authorization.v1.harness;

import mycelium.command_authorization.v1.fixtures.AuthorizationVariants;
import mycelium.command_authorization.v1.model.CommandAuthorization;
import mycelium.harness.ContractRun;

import java.util.List;

public final class Run {
    private Run() {
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("command.authorization@1",
                CommandAuthorizationContractTests::reference,
                List.of(
                        new ContractRun.Broken("GrantRequiresFallback",
                                () -> new CommandAuthorizationContractTests(AuthorizationVariants::grantRequiresFallback).run(),
                                "A1:grant-is-authoritative"),
                        new ContractRun.Broken("DenyAllowsPrivileged",
                                () -> new CommandAuthorizationContractTests(AuthorizationVariants::denyAllowsPrivileged).run(),
                                "A2:deny-is-authoritative"),
                        new ContractRun.Broken("UnsetAlwaysAllows",
                                () -> new CommandAuthorizationContractTests(AuthorizationVariants::unsetAlwaysAllows).run(),
                                "A3:unset-uses-fallback"),
                        new ContractRun.Broken("NoOpAuthorization",
                                () -> new CommandAuthorizationContractTests(AuthorizationVariants::noOp).run(),
                                "A1:grant-is-authoritative")
                ));
        System.exit(exit);
    }
}
