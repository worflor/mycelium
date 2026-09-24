package mycelium.data_catalog.v1.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reference catalog: sources ordered by (pack, id) so a later pack's file replaces an earlier one's, each parsed
 * on its own (a failure is a rejection, never an escape), the result frozen and swapped in whole.
 */
public final class ReferenceCatalog<T> implements Catalog<T> {
    private volatile Snapshot<T> current = Snapshot.empty();

    @Override
    public Snapshot<T> current() {
        return current;
    }

    @Override
    public Snapshot<T> reload(List<Source> sources, Parser<T> parser) {
        List<Source> ordered = new ArrayList<>(sources);
        ordered.sort(Comparator.comparingInt(Source::packOrder).thenComparing(Source::id));
        Map<String, Source> winners = new HashMap<>();
        for (Source s : ordered) {
            winners.put(s.id(), s); // later pack wins
        }
        Map<String, T> entries = new HashMap<>();
        List<Rejection> rejected = new ArrayList<>();
        for (Source s : winners.values()) {
            try {
                T entry = parser.parse(s.id(), s.text());
                if (entry == null) {
                    rejected.add(new Rejection(s.id(), "parsed to nothing"));
                } else {
                    entries.put(s.id(), entry);
                }
            } catch (Exception | Error e) {
                if (e instanceof VirtualMachineError vme) {
                    throw vme;
                }
                rejected.add(new Rejection(s.id(), String.valueOf(e.getMessage())));
            }
        }
        rejected.sort(Comparator.comparing(Rejection::id));
        Snapshot<T> next = new Snapshot<>(current.generation() + 1, entries, rejected);
        current = next;
        return next;
    }
}
