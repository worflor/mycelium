package mycelium.chat_invocation.v1.harness;

import mycelium.chat_invocation.v1.fixtures.FlexGate;
import mycelium.chat_invocation.v1.fixtures.FlexGate.Flaw;
import mycelium.chat_invocation.v1.model.ChatGate;
import mycelium.harness.ContractRun;

import java.util.Arrays;

public final class Run {
    private Run() {
    }

    private static ContractRun.Broken broken(String name, Flaw flaw, String label) {
        return new ContractRun.Broken(name,
                () -> new ChatInvocationContractTests(r -> new FlexGate(r, flaw)).run(), label);
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("chat.invocation@1",
                () -> {
                    // two independent correct gates: the model's reference and FlexGate with no flaw
                    mycelium.harness.Check reference = new ChatInvocationContractTests(ChatGate::new).run();
                    mycelium.harness.Check second = new ChatInvocationContractTests(r -> new FlexGate(r, Flaw.NONE)).run();
                    for (String f : second.failures()) {
                        reference.fail("FlexGate(NONE) " + f);
                    }
                    return reference;
                },
                Arrays.asList(
                        broken("NoOpGate", Flaw.NO_OP, "P1:invocation-delivered"),
                        broken("LeakyGate", Flaw.LEAKY, "L1:non-invocation-never-delivered"),
                        broken("EagerGate", Flaw.EAGER, "L1:prefix-needs-a-boundary"),
                        broken("UnboundedGate", Flaw.UNBOUNDED, "L2:snapshot-bounded"),
                        broken("StaleGate", Flaw.STALE, "L3:snapshot-fresh"),
                        broken("EchoGate", Flaw.ECHO, "L4:invocations-private"),
                        broken("GreedyGate", Flaw.GREEDY, "L5:rate-bounded"),
                        broken("DeafGate", Flaw.DEAF, "L6:opt-out-honoured"),
                        broken("TruncatingGate", Flaw.TRUNCATING, "L7:no-silent-truncation"),
                        broken("ShuffledGate", Flaw.SHUFFLED, "P2:snapshot-in-order"),
                        broken("StingyGate", Flaw.STINGY, "P3:under-budget-admitted"),
                        broken("MuteWaitGate", Flaw.MUTE_WAIT, "P4:ready-in-is-exact"),
                        broken("EarlyWaitGate", Flaw.EARLY_WAIT, "P4:ready-in-is-exact")
                ));
        System.exit(exit);
    }
}
