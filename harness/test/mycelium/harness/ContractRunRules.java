package mycelium.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * The rules {@link ContractRun} keeps for every package, proven on made-up matrices: a clean reference with every entry
 * caught passes; a matrix without a {@code NoOp*} entry fails however it is written; a missed entry fails; the report
 * says what ran. The runners run it with {@link SeededIdentity}, before any package.
 */
public final class ContractRunRules {
    private static int failures;

    public static void main(String[] args) throws IOException {
        Supplier<Check> clean = Check::new;
        Supplier<Check> caught = () -> {
            Check c = new Check();
            c.fail("L1:law");
            return c;
        };

        that(ContractRun.run("rules.pass@1", clean, List.of(new ContractRun.Broken("NoOpThing", caught, "L1:law"))) == 0,
                "a clean reference and a caught NoOp entry pass");
        that(ContractRun.run("rules.no-noop@1", clean, List.of(new ContractRun.Broken("IdleThing", caught, "L1:law"))) == 1,
                "a matrix without a NoOp entry fails");
        that(ContractRun.run("rules.missed@1", clean, List.of(new ContractRun.Broken("NoOpThing", Check::new, "L1:law"))) == 1,
                "a NoOp entry nothing catches fails");

        Path report = Files.createTempFile("mycelium-contract-run", ".json");
        String before = System.getProperty(ContractRun.REPORT);
        System.setProperty(ContractRun.REPORT, report.toString());
        try {
            ContractRun.run("rules.report@1", clean, List.of(
                    new ContractRun.Broken("NoOpThing", caught, "L1:law"),
                    new ContractRun.Broken("Quote\"Thing", Check::new, "L2:other")));
            String text = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
            that(text.contains("\"contract\":\"rules.report@1\""), "the report names the contract");
            that(text.contains("{\"name\":\"NoOpThing\",\"intended\":\"L1:law\",\"caught\":true}"), "the report lists a caught entry");
            that(text.contains("{\"name\":\"Quote\\\"Thing\",\"intended\":\"L2:other\",\"caught\":false}"), "the report lists a missed entry, escaped");
            that(text.contains("\"noOp\":true") && text.contains("\"exit\":1"), "the report carries the rule and the exit");
        } finally {
            if (before == null) {
                System.clearProperty(ContractRun.REPORT);
            } else {
                System.setProperty(ContractRun.REPORT, before);
            }
            Files.deleteIfExists(report);
        }

        System.out.println();
        System.out.println("ContractRun rules: " + (failures == 0 ? "PASS" : "FAIL (" + failures + ")"));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void that(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("RULE BROKEN: " + what);
        }
    }
}
