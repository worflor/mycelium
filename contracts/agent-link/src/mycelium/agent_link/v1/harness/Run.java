package mycelium.agent_link.v1.harness;

import mycelium.agent_link.v1.fixtures.BadBackoffs;
import mycelium.agent_link.v1.fixtures.FlexLink;
import mycelium.agent_link.v1.fixtures.FlexLink.Flaw;
import mycelium.agent_link.v1.model.LinkSession;
import mycelium.harness.Check;
import mycelium.harness.ContractRun;

import java.util.Arrays;

public final class Run {
    private Run() {
    }

    private static ContractRun.Broken link(String name, Flaw flaw, String label) {
        return new ContractRun.Broken(name, () -> new AgentLinkContractTests(
                (t, h, l) -> new FlexLink(t, h, l, flaw), AgentLinkContractTests::reference).run(), label);
    }

    private static ContractRun.Broken backoff(String name, AgentLinkContractTests.Delays delays, String label) {
        return new ContractRun.Broken(name, () -> new AgentLinkContractTests(LinkSession::new, delays).run(), label);
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("agent.link@1",
                () -> {
                    // two independent correct links: the model's reference and FlexLink with no flaw
                    Check reference = new AgentLinkContractTests(LinkSession::new, AgentLinkContractTests::reference).run();
                    Check second = new AgentLinkContractTests((t, h, l) -> new FlexLink(t, h, l, Flaw.NONE),
                            AgentLinkContractTests::reference).run();
                    for (String f : second.failures()) {
                        reference.fail("FlexLink(NONE) " + f);
                    }
                    return reference;
                },
                Arrays.asList(
                        link("NoOpLink", Flaw.NO_OP, "P1:executes-and-answers"),
                        link("ForgetfulLink", Flaw.FORGETFUL, "L1:at-most-once"),
                        link("RacingLink", Flaw.RACING, "L1:no-concurrent-twin"),
                        link("AmnesiacLink", Flaw.AMNESIAC, "L2:dedup-survives-reconnect"),
                        link("TrustingLink", Flaw.TRUSTING, "L3:no-work-before-hello"),
                        link("HoardingLink", Flaw.HOARDING, "L4:memory-bounded"),
                        link("GluttonLink", Flaw.GLUTTON, "L5:oversize-refused"),
                        link("GuessingLink", Flaw.GUESSING, "L6:unknown-refused"),
                        link("RetryingLink", Flaw.RETRYING, "L7:failure-is-remembered"),
                        link("MisroutedLink", Flaw.MISROUTED, "P2:reply-correlates"),
                        link("StaleReadLink", Flaw.STALE_READS, "P3:reads-are-fresh"),
                        backoff("OverflowBackoff", BadBackoffs::overflow, "B3:never-gives-up"),
                        backoff("GiveUpBackoff", BadBackoffs::givesUp, "B3:never-gives-up"),
                        backoff("UncappedBackoff", BadBackoffs::uncapped, "B1:delay-within-bounds"),
                        backoff("SlowStartBackoff", BadBackoffs::slowStart, "P4:first-retry-prompt")
                ));
        System.exit(exit);
    }
}
