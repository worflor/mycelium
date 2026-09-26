package mycelium.harness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The standard contract harness shape (DESIGN §11, §14):
 * a reference implementation must be clean, and every deliberately incorrect implementation in the
 * rejection matrix must be caught for its intended label. A green exit code is one input to acceptance,
 * never the decision.
 *
 * <p>Every package runs on this class, so the rules every matrix keeps are kept here, where the matrix is run, whatever
 * shape a package's {@code Run} has. One rule is kept by name: the matrix holds a do-nothing implementation, named
 * {@code NoOp*}, because a contract whose laws a no-op satisfies is not a contract (CONTRACT-PACKAGE.md).
 *
 * <p>With {@code -Dmycelium.harness.report=<file>} the run is also written there as JSON, so tools read what ran
 * instead of parsing a package's source ({@code myceliumVerify} collects it; the repository check counts from it).
 */
public final class ContractRun {
    /** A deliberately incorrect implementation and the label that must fail when it is exercised. */
    public record Broken(String name, Supplier<Check> run, String intendedLabel) {
    }

    /** The prefix of the do-nothing implementation every matrix must hold. */
    public static final String NO_OP = "NoOp";

    /** Where the JSON report goes, when set. */
    public static final String REPORT = "mycelium.harness.report";

    private ContractRun() {
    }

    /**
     * Prints a report and returns the exit code: reference failures, plus missed rejections, plus one if the matrix
     * holds no {@code NoOp*} entry.
     *
     * @param contractId e.g. {@code inventory.transfer@1}
     * @param reference  runs the fixtures against the reference implementation
     * @param matrix     the rejection matrix
     */
    public static int run(String contractId, Supplier<Check> reference, List<Broken> matrix) {
        System.out.println("Mycelium - " + contractId + " contract harness");
        System.out.println();

        Check referenceCheck = reference.get();
        System.out.println("Reference: " + (referenceCheck.clean() ? "CLEAN" : "FAILURES")
                + " (" + referenceCheck.assertions() + " assertions)");
        for (String failure : referenceCheck.failures()) {
            System.out.println("  - " + failure);
        }

        int missed = 0;
        boolean noOp = false;
        List<String> entries = new ArrayList<>();
        System.out.println();
        System.out.println("Rejection matrix (each broken implementation must be caught):");
        for (Broken entry : matrix) {
            Check check;
            try {
                check = entry.run().get();
            } catch (RuntimeException e) {
                check = new Check();
                check.fail("harness:threw " + e);
            }
            boolean caught = check.has(entry.intendedLabel());
            if (!caught) {
                missed++;
            }
            noOp |= entry.name().startsWith(NO_OP);
            entries.add("{\"name\":" + json(entry.name()) + ",\"intended\":" + json(entry.intendedLabel())
                    + ",\"caught\":" + caught + "}");
            System.out.println("  " + entry.name() + ": " + (caught ? "CAUGHT" : "MISSED")
                    + " (intended: " + entry.intendedLabel() + ")");
            for (String failure : check.failures()) {
                System.out.println("      - " + failure);
            }
        }
        if (!noOp) {
            System.out.println("  (no " + NO_OP + "* entry: a do-nothing implementation must fail every contract, and the"
                    + " matrix must show it does; CONTRACT-PACKAGE.md)");
        }

        int exit = referenceCheck.failures().size() + missed + (noOp ? 0 : 1);
        System.out.println();
        System.out.println("Result: " + (exit == 0 ? "PASS" : "FAIL")
                + " (reference failures: " + referenceCheck.failures().size()
                + ", missed rejections: " + missed + (noOp ? "" : ", no " + NO_OP + " entry")
                + ", matrix size: " + matrix.size() + ")");
        report(contractId, referenceCheck, entries, noOp, exit);
        return exit;
    }

    private static void report(String contractId, Check reference, List<String> entries, boolean noOp, int exit) {
        String to = System.getProperty(REPORT);
        if (to == null || to.isEmpty()) {
            return;
        }
        List<String> failures = new ArrayList<>();
        for (String f : reference.failures()) {
            failures.add(json(f));
        }
        String text = "{\"schema\":1,\"contract\":" + json(contractId)
                + ",\"reference\":{\"clean\":" + reference.clean() + ",\"assertions\":" + reference.assertions()
                + ",\"failures\":[" + String.join(",", failures) + "]}"
                + ",\"matrix\":[" + String.join(",", entries) + "]"
                + ",\"noOp\":" + noOp + ",\"exit\":" + exit + "}\n";
        try {
            Path path = Paths.get(to);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the harness report to " + to, e);
        }
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
