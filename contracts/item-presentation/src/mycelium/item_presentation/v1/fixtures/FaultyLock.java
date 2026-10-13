package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A compiler that keeps the codes the lock has and gets what it does for a selection the lock lacks wrong (law S3). The
 * {@link Flaw} says how.
 */
public final class FaultyLock extends ReferenceSigil {
    /** The mistakes, each a realistic way to handle a selection with no code yet. */
    public enum Flaw {
        /** Gives the selection the next code itself and compiles, so the committed lock never records it. */
        INVENTS_MISSING_CODES,
        /** Lists the lines to add with the namespace dropped from the model reference: they do not read back as lock lines. */
        LINES_LACK_NAMESPACE,
        /** Lists lines whose codes start one above the next free code, leaving a gap. */
        LINES_SKIP_A_CODE
    }

    private final Flaw flaw;

    public FaultyLock(Flaw flaw) {
        this.flaw = flaw;
    }

    @Override
    protected Map<String, Integer> assignCodes(String item, List<String> keys, Lock lock, List<Diagnostic> found) {
        if (flaw == Flaw.INVENTS_MISSING_CODES) {
            Map<String, Integer> codes = new TreeMap<>();
            List<String> missing = new ArrayList<>();
            for (String key : keys) {
                Integer code = lock.codeOf(item, key);
                if (code == null) {
                    missing.add(key);
                } else {
                    codes.put(key, code);
                }
            }
            for (Lock.Entry e : lock.allocate(item, missing)) {
                codes.put(e.key(), e.code());
            }
            return codes;
        }
        Map<String, Integer> codes = super.assignCodes(item, keys, lock, found);
        for (int i = 0; i < found.size(); i++) {
            Diagnostic d = found.get(i);
            if (d.kind() != Diagnostic.Kind.LOCK_MISSING) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (String line : d.lockLines()) {
                lines.add(rewritten(line));
            }
            found.set(i, new Diagnostic(d.kind(), d.where(), d.message(), lines));
        }
        return codes;
    }

    private String rewritten(String line) {
        if (flaw == Flaw.LINES_LACK_NAMESPACE) {
            return line.replaceAll("\"key\": \"[a-z0-9_.\\-]+:", "\"key\": \"");
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"code\": (\\d+)").matcher(line);
        return m.find() ? line.substring(0, m.start(1)) + (Integer.parseInt(m.group(1)) + 1) + line.substring(m.end(1)) : line;
    }
}
