package mycelium.item_presentation.v1.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The whole compile, over text, for a build tool: programs, the component catalog and the lock go in as JSON text, and one
 * JSON report comes out (diagnostics, the files to write, and for {@link #lock} the lock as it should be). It takes and
 * returns only text and maps of text, so a build can call it across a class loader with nothing in common but the JDK.
 * It reads no file and no clock.
 */
public final class SigilTool {
    private SigilTool() {
    }

    /**
     * Compiles every program for a target.
     *
     * @param minecraft   the version, only to name it in a refusal
     * @param realization the realization the era table gives that version ({@code item-definition}, {@code model-overrides},
     *                    {@code none})
     * @param programs    each program's file name to its text
     * @return the report: {@code ok}, {@code diagnostics}, {@code files} (path to text), {@code items}
     */
    public static String compile(String minecraft, String realization, Map<String, String> programs, String components, String lock) {
        Report report = new Report();
        Realization r;
        try {
            r = Realization.parse(realization);
        } catch (IllegalArgumentException e) {
            report.add("target", new Diagnostic(Diagnostic.Kind.SCHEMA, "realization", e.getMessage()));
            return report.write(minecraft, realization, null);
        }
        Target target = new Target(minecraft, r);
        Loaded loaded = load(programs, components, lock, report);
        if (loaded == null) {
            return report.write(minecraft, realization, null);
        }
        SigilCompiler compiler = new ReferenceSigil();
        Map<String, String> files = new TreeMap<>();
        for (Map.Entry<String, Program> e : loaded.programs.entrySet()) {
            Compilation c = compiler.compile(e.getValue(), loaded.lock, loaded.catalog, target);
            if (c instanceof Compilation.Rejected rejected) {
                report.addAll(e.getKey(), rejected.diagnostics());
            } else {
                Compilation.Compiled done = (Compilation.Compiled) c;
                for (Map.Entry<String, String> f : done.artefacts().entrySet()) {
                    if (files.put(f.getKey(), f.getValue()) != null) {
                        report.add(e.getKey(), new Diagnostic(Diagnostic.Kind.SCHEMA, "item", "two programs write " + f.getKey()));
                    }
                }
                report.item(e.getKey(), done.plan());
            }
        }
        if (report.ok() && !files.isEmpty()) {
            files.put(Layout.catalog(), loaded.catalog.toJson());
        }
        return report.write(minecraft, realization, report.ok() ? files : null);
    }

    /**
     * Completes the lock: every selection of every program that the lock lacks gets the next code, in sorted key order.
     * The report's {@code lock} is the whole new file and {@code added} the lines that were appended; nothing is added
     * to a lock while any program is refused for another reason.
     */
    public static String lock(Map<String, String> programs, String components, String lock) {
        Report report = new Report();
        Loaded loaded = load(programs, components, lock, report);
        if (loaded == null) {
            return report.write("", "", null);
        }
        SigilCompiler compiler = new ReferenceSigil();
        Lock next = loaded.lock;
        Target target = new Target("lock", Realization.ITEM_DEFINITION);
        List<Object> added = new ArrayList<>();
        for (Map.Entry<String, Program> e : loaded.programs.entrySet()) {
            Compilation c = compiler.compile(e.getValue(), next, loaded.catalog, target);
            if (c instanceof Compilation.Rejected rejected) {
                List<Diagnostic> others = new ArrayList<>();
                List<String> lines = new ArrayList<>();
                for (Diagnostic d : rejected.diagnostics()) {
                    if (d.kind() == Diagnostic.Kind.LOCK_MISSING) {
                        lines.addAll(d.lockLines());
                    } else {
                        others.add(d);
                    }
                }
                if (!others.isEmpty()) {
                    report.addAll(e.getKey(), others);
                } else {
                    next = next.apply(e.getValue().item(), lines);
                    for (String line : lines) {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("item", e.getValue().item());
                        one.put("line", line);
                        added.add(one);
                    }
                }
            }
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        if (report.ok()) {
            extra.put("lock", next.toJson());
            extra.put("added", added);
        }
        return report.write("", "", null, extra);
    }

    private record Loaded(Map<String, Program> programs, ComponentCatalog catalog, Lock lock) {
    }

    private static Loaded load(Map<String, String> programs, String components, String lock, Report report) {
        Result<ComponentCatalog> catalog = ComponentCatalog.parse(components);
        if (catalog instanceof Result.Failed<ComponentCatalog> failed) {
            report.addAll("components", failed.diagnostics());
        }
        Result<Lock> locked = Lock.parse(lock);
        if (locked instanceof Result.Failed<Lock> failed) {
            report.addAll("lock", failed.diagnostics());
        }
        Map<String, Program> parsed = new TreeMap<>();
        Map<String, String> itemFile = new TreeMap<>();
        for (Map.Entry<String, String> e : new TreeMap<>(programs).entrySet()) {
            Result<Program> p = Program.parse(e.getValue());
            if (p instanceof Result.Failed<Program> failed) {
                report.addAll(e.getKey(), failed.diagnostics());
                continue;
            }
            Program program = ((Result.Ok<Program>) p).value();
            String other = itemFile.put(program.item(), e.getKey());
            if (other != null) {
                report.add(e.getKey(), new Diagnostic(Diagnostic.Kind.SCHEMA, "item", program.item() + " is also the item of " + other
                        + ": one program per item"));
                continue;
            }
            parsed.put(e.getKey(), program);
        }
        if (!report.ok()) {
            return null;
        }
        return new Loaded(parsed, ((Result.Ok<ComponentCatalog>) catalog).value(), ((Result.Ok<Lock>) locked).value());
    }

    private static final class Report {
        private final List<Object> diagnostics = new ArrayList<>();
        private final List<Object> items = new ArrayList<>();

        boolean ok() {
            return diagnostics.isEmpty();
        }

        void add(String file, Diagnostic d) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", d.kind().name());
            m.put("where", file + ": " + d.where());
            m.put("message", d.message());
            m.put("lockLines", new ArrayList<Object>(d.lockLines()));
            diagnostics.add(m);
        }

        void addAll(String file, List<Diagnostic> all) {
            for (Diagnostic d : all) {
                add(file, d);
            }
        }

        void item(String file, Plan plan) {
            if (plan == null) {
                return;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", file);
            m.put("item", plan.item());
            m.put("inputs", Long.valueOf(plan.program().inputs().size()));
            m.put("cases", Long.valueOf(plan.program().cases().size()));
            m.put("selections", Long.valueOf(plan.entries().size()));
            items.add(m);
        }

        String write(String minecraft, String realization, Map<String, String> files) {
            return write(minecraft, realization, files, new LinkedHashMap<String, Object>());
        }

        String write(String minecraft, String realization, Map<String, String> files, Map<String, Object> extra) {
            Map<String, Object> root = new LinkedHashMap<>(extra);
            root.put("schema", Long.valueOf(1));
            root.put("ok", Boolean.valueOf(ok()));
            root.put("minecraft", minecraft);
            root.put("realization", realization);
            root.put("diagnostics", diagnostics);
            root.put("items", items);
            root.put("files", files == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(files));
            return Json.write(root);
        }
    }
}
