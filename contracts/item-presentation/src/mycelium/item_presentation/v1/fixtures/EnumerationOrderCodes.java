package mycelium.item_presentation.v1.fixtures;

import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.ReferenceSigil;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Numbers selections 1, 2, 3 in the order it enumerates them, ignoring the lock: adding a case renumbers the others (S3). */
public final class EnumerationOrderCodes extends ReferenceSigil {
    @Override
    protected Map<String, Integer> assignCodes(String item, List<String> keys, Lock lock, List<Diagnostic> found) {
        Map<String, Integer> codes = new TreeMap<>();
        int next = 1;
        for (String key : keys) {
            codes.put(key, next++);
        }
        return codes;
    }
}
