package mycelium.agent_link.v1.harness;

import mycelium.agent_link.v1.fixtures.AdmissionFixtures;
import mycelium.agent_link.v1.fixtures.BadBackoffs;
import mycelium.agent_link.v1.fixtures.FlexLink;
import mycelium.agent_link.v1.fixtures.FlexLink.Flaw;
import mycelium.agent_link.v1.model.AdmissionBudget;
import mycelium.agent_link.v1.model.LinkSession;
import mycelium.harness.Check;
import mycelium.harness.ContractRun;

import java.util.Arrays;

public final class Run {
    private Run() {
    }

    /** A flawed link, configured to violate one law whether it keeps its own memory or uses the handed ledger. */
    private static ContractRun.Broken link(String name, Flaw flaw, String label) {
        return new ContractRun.Broken(name, () -> new AgentLinkContractTests(
                (t, h, l) -> new FlexLink(t, h, l, flaw), AgentLinkContractTests::reference,
                (t, h, l, ledger) -> new FlexLink(t, h, l, flaw, ledger)).run(), label);
    }

    private static ContractRun.Broken backoff(String name, AgentLinkContractTests.Delays delays, String label) {
        return new ContractRun.Broken(name, () -> new AgentLinkContractTests(LinkSession::new, delays, LinkSession::new).run(), label);
    }

    private static ContractRun.Broken admission(String name, AdmissionContractTests.Budgets budgets, String label) {
        return new ContractRun.Broken(name, () -> new AdmissionContractTests(budgets).run(), label);
    }

    private static Check reference() {
        Check result = new AgentLinkContractTests(LinkSession::new, AgentLinkContractTests::reference,
                LinkSession::new).run();
        Check independent = new AgentLinkContractTests((t, h, l) -> new FlexLink(t, h, l, Flaw.NONE),
                AgentLinkContractTests::reference,
                (t, h, l, ledger) -> new FlexLink(t, h, l, Flaw.NONE, ledger)).run();
        for (String failure : independent.failures()) {
            result.fail("FlexLink(NONE) " + failure);
        }
        Check admission = new AdmissionContractTests(AdmissionBudget::new).run();
        for (String failure : admission.failures()) {
            result.fail("AdmissionBudget " + failure);
        }
        return result;
    }

    public static void main(String[] args) {
        int exit = ContractRun.run("agent.link@1",
                Run::reference,
                Arrays.asList(
                        admission("NoOpAdmission", (jobs, bytes) -> new AdmissionFixtures.NoOp(),
                                "P5:admits-available-work"),
                        admission("JobOverbookAdmission", AdmissionFixtures.JobOverbook::new,
                                "L11:bounded-admission"),
                        admission("ByteOverbookAdmission", AdmissionFixtures.ByteOverbook::new,
                                "L11:bounded-admission"),
                        admission("RejectedCostLeakAdmission", AdmissionFixtures.RejectedCostLeak::new,
                                "L12:admission-release"),
                        admission("LeakyLeaseAdmission", AdmissionFixtures.LeakyLease::new,
                                "L12:admission-release"),
                        admission("RacingAdmission", AdmissionFixtures.Racing::new,
                                "L11:bounded-admission"),
                        link("NoOpLink", Flaw.NO_OP, "P1:executes-and-answers"),
                        link("ForgetfulLink", Flaw.FORGETFUL, "L1:at-most-once"),
                        link("RacingLink", Flaw.RACING, "L1:no-concurrent-twin"),
                        link("AmnesiacLink", Flaw.AMNESIAC, "L2:dedup-survives-reconnect"),
                        link("TrustingLink", Flaw.TRUSTING, "L3:no-work-before-hello"),
                        link("HoardingLink", Flaw.HOARDING, "L4:memory-bounded"),
                        link("GluttonLink", Flaw.GLUTTON, "L5:oversize-refused"),
                        link("GuessingLink", Flaw.GUESSING, "L6:unknown-refused"),
                        link("RetryingLink", Flaw.RETRYING, "L7:failure-is-remembered"),
                        link("AbandoningErrorLink", Flaw.ABANDON_ERROR, "L7:failure-is-remembered"),
                        link("AbandoningErrorHandoverLink", Flaw.ABANDON_ERROR, "L8:dedup-survives-handover"),
                        link("MissingRefusalReplayLink", Flaw.NO_REFUSAL_REPLAY, "L10:failed-refusal-replayed"),
                        link("OpaqueFailureLink", Flaw.OPAQUE_FAILURE, "L9:failure-has-diagnostic"),
                        // a correct link in every other way that keeps its own memory whatever ledger it is given:
                        // the replaced consumer's requests in flight run twice
                        new ContractRun.Broken("ParochialLink", () -> new AgentLinkContractTests(
                                LinkSession::new, AgentLinkContractTests::reference,
                                (t, h, l, ledger) -> new LinkSession(t, h, l)).run(), "L8:dedup-survives-handover"),
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
