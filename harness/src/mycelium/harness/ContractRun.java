package mycelium.harness;

import java.util.List;
import java.util.function.Supplier;

/**
 * The standard contract harness shape (DESIGN §11, §14):
 * a reference implementation must be clean, and every deliberately incorrect implementation in the
 * rejection matrix must be caught for its intended label. A green exit code is one input to acceptance,
 * never the decision.
 */
public final class ContractRun {
    /** A deliberately incorrect implementation and the label that must fail when it is exercised. */
    public record Broken(String name, Supplier<Check> run, String intendedLabel) {
    }

    private ContractRun() {
    }

    /**
     * Prints a report and returns the exit code: reference failures plus missed rejections.
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
            System.out.println("  " + entry.name() + ": " + (caught ? "CAUGHT" : "MISSED")
                    + " (intended: " + entry.intendedLabel() + ")");
            for (String failure : check.failures()) {
                System.out.println("      - " + failure);
            }
        }

        int exit = referenceCheck.failures().size() + missed;
        System.out.println();
        System.out.println("Result: " + (exit == 0 ? "PASS" : "FAIL")
                + " (reference failures: " + referenceCheck.failures().size()
                + ", missed rejections: " + missed + ", matrix size: " + matrix.size() + ")");
        return exit;
    }
}
