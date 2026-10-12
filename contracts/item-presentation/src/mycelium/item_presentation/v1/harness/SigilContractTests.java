package mycelium.item_presentation.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentId;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.RecordCodec;
import mycelium.item_presentation.v1.model.CarrierView;
import mycelium.item_presentation.v1.model.Case;
import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Condition;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.Realization;
import mycelium.item_presentation.v1.model.ReferencePlan;
import mycelium.item_presentation.v1.model.Result;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.SigilCompiler;
import mycelium.item_presentation.v1.model.SigilTool;
import mycelium.item_presentation.v1.model.Target;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The laws of {@code item.presentation@1} (S1 to S7), held against one compiler. A law's label is its surface: the
 * rejection matrix names the label each broken compiler must fail.
 */
public final class SigilContractTests {
    static final Target MODERN = new Target("modern", Realization.ITEM_DEFINITION);
    static final Target LEGACY = new Target("legacy", Realization.MODEL_OVERRIDES);

    private final SigilCompiler compiler;
    private final Check c = new Check();
    private final ComponentCatalog catalog = Programs.catalog();

    public SigilContractTests(SigilCompiler compiler) {
        this.compiler = compiler;
    }

    public Check run() {
        program(Programs.band(), "band");
        program(Programs.spool(), "spool");
        program(Programs.mini("example:panel", "example:item/panel_base", "", "{\"model\": \"example:item/panel_lit\"}"), "constant");
        program(Programs.mini("example:panel", "example:item/panel_base", "", ""), "empty");
        stableCodes();
        projection();
        bounded();
        determinism();
        evaluation();
        realization();
        wellFormed();
        adapter();
        tool();
        properties();
        return c;
    }

    private void ok(boolean cond, String label, Supplier<String> detail) {
        c.that(cond, cond ? label : label + " " + detail.get());
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** The lock a build would have committed for the program, from the oracle's own enumeration. */
    static Lock lockFor(Program p) {
        TreeSet<String> keys = new TreeSet<>();
        Program pruned = p.pruned();
        for (Map<String, Object> tuple : Evaluator.tuples(pruned.inputs())) {
            keys.add(Evaluator.evaluate(pruned, tuple));
        }
        keys.remove(p.base());
        Lock empty = Lock.empty();
        return empty.plus(p.item(), empty.allocate(p.item(), new ArrayList<>(keys)));
    }

    private Compilation.Compiled compiled(Program p, Lock lock, Target target, String tag) {
        Compilation result;
        try {
            result = compiler.compile(p, lock, catalog, target);
        } catch (RuntimeException e) {
            c.fail("S1:compiles (" + tag + ", " + target.minecraft() + ": threw " + e + ")");
            return null;
        }
        if (result instanceof Compilation.Rejected r) {
            c.fail("S1:compiles (" + tag + ", " + target.minecraft() + ": " + r.diagnostics() + ")");
            return null;
        }
        return (Compilation.Compiled) result;
    }

    private static boolean sameSelection(Selection a, Selection b) {
        return a.model().equals(b.model()) && a.code() == b.code() && a.key().equals(b.key());
    }

    // --- S1, S2, S4, S6 over one program, for both eras ------------------------------------------------------------

    private void program(Program p, String tag) {
        Lock lock = lockFor(p);
        Compilation.Compiled modern = compiled(p, lock, MODERN, tag);
        Compilation.Compiled legacy = compiled(p, lock, LEGACY, tag);
        List<Map<String, Object>> tuples = Evaluator.tuples(p.pruned().inputs());
        for (Compilation.Compiled done : new Compilation.Compiled[] {modern, legacy}) {
            if (done == null) {
                continue;
            }
            boolean isModern = done == modern;
            String era = isModern ? "modern" : "legacy";
            Plan plan = done.plan();
            String artefact = done.artefacts().get(isModern ? Layout.definition(p.item()) : Layout.model(p.item()));
            ok(artefact != null, "S2:artefact-emitted", () -> tag + " " + era + ": none at the era's path");
            Plan reread = null;
            String planText = done.artefacts().get(Layout.plan(p.item()));
            ok(planText != null, "S2:plan-resource-agrees", () -> tag + " " + era + ": no plan resource");
            if (planText != null) {
                Result<Plan> parsed = ReferencePlan.parse(planText);
                ok(parsed instanceof Result.Ok, "S2:plan-resource-agrees", () -> tag + " " + era + ": unreadable: " + parsed);
                if (parsed instanceof Result.Ok<Plan> good) {
                    reread = good.value();
                }
            }
            for (Map<String, Object> tuple : tuples) {
                String oracle = Evaluator.evaluate(p, tuple);
                Selection sel;
                try {
                    sel = plan.select(tuple);
                } catch (RuntimeException e) {
                    c.fail("S1:totality-agrees-with-evaluator (" + tag + " " + era + " " + tuple + ": threw " + e + ")");
                    continue;
                }
                ok(sel != null && sel.model().equals(oracle), "S1:totality-agrees-with-evaluator",
                        () -> tag + " " + era + " " + tuple + ": the cases choose " + oracle + ", the plan " + (sel == null ? null : sel.model()));
                if (sel == null) {
                    continue;
                }
                boolean base = oracle.equals(p.base());
                boolean listed = false;
                for (Plan.Entry e : plan.entries()) {
                    listed |= e.key().equals(sel.key()) && e.code() == sel.code();
                }
                ok(base ? sel.isBase() : (listed && sel.key().equals(oracle)), "S1:one-selection-per-tuple",
                        () -> tag + " " + era + " " + tuple + ": " + sel + " is not the plan's selection for " + oracle);

                Selector selector = plan.selectorOf(sel);
                String drawn;
                try {
                    drawn = isModern ? Simulators.modern(artefact, selector) : Simulators.legacy(artefact, selector);
                } catch (RuntimeException e) {
                    c.fail((isModern ? "S2:modern-draws-evaluated-model" : "S2:legacy-draws-evaluated-model") + " (" + tag + " "
                            + tuple + ": the artefact cannot be read: " + e.getMessage() + ")");
                    continue;
                }
                ok(oracle.equals(drawn), isModern ? "S2:modern-draws-evaluated-model" : "S2:legacy-draws-evaluated-model",
                        () -> tag + " " + tuple + ": the cases choose " + oracle + ", the era draws " + drawn + " for " + selector);

                Selection viaView = plan.select(Programs.MapView.of(p, tuple));
                ok(sameSelection(sel, viaView) && viaView.inDomain(), "S2:adapter-reading-agrees",
                        () -> tag + " " + era + " " + tuple + ": reading the stack gave " + viaView + ", the tuple " + sel);
                if (reread != null) {
                    Selection again;
                    try {
                        again = reread.select(tuple);
                    } catch (RuntimeException e) {
                        c.fail("S2:plan-resource-agrees (" + tag + " " + era + " " + tuple + ": threw " + e + ")");
                        continue;
                    }
                    ok(sameSelection(sel, again), "S2:plan-resource-agrees",
                            () -> tag + " " + era + " " + tuple + ": the resource selects " + again + ", the plan " + sel);
                }
            }
            if (!isModern && artefact != null) {
                try {
                    List<Long> predicates = Simulators.predicates(artefact);
                    boolean ascending = true;
                    for (int i = 0; i < predicates.size(); i++) {
                        ascending &= predicates.get(i) >= 1 && (i == 0 || predicates.get(i) > predicates.get(i - 1));
                    }
                    ok(ascending, "S4:overrides-strictly-ascending", () -> tag + ": overrides are written " + predicates);
                } catch (RuntimeException e) {
                    c.fail("S4:overrides-strictly-ascending (" + tag + ": cannot be read: " + e.getMessage() + ")");
                }
            }
            if (isModern && artefact != null) {
                try {
                    List<String> keys = Simulators.caseKeys(artefact);
                    List<String> sorted = new ArrayList<>(keys);
                    Collections.sort(sorted);
                    ok(keys.equals(sorted), "S6:cases-in-key-order", () -> tag + ": cases are written " + keys);
                } catch (RuntimeException e) {
                    c.fail("S6:cases-in-key-order (" + tag + ": cannot be read: " + e.getMessage() + ")");
                }
            }
            // S6: the same inputs, the same bytes, in canonical form
            Compilation.Compiled again = compiled(p, lock, isModern ? MODERN : LEGACY, tag + " again");
            if (again != null) {
                ok(again.artefacts().equals(done.artefacts()), "S6:repeat-identical", () -> tag + " " + era + ": two compilations differ");
            }
            for (Map.Entry<String, String> file : done.artefacts().entrySet()) {
                String text = file.getValue();
                boolean canonical;
                try {
                    canonical = Json.write(Json.parse(text)).equals(text);
                } catch (RuntimeException e) {
                    canonical = false;
                }
                boolean plain = text.indexOf('\r') < 0 && text.endsWith("\n") && text.chars().allMatch(ch -> ch == '\n' || (ch >= 0x20 && ch < 0x7f));
                final boolean isCanonical = canonical;
                ok(isCanonical, "S6:canonical-json", () -> tag + " " + era + " " + file.getKey() + " is not the canonical form of its own content");
                ok(plain, "S6:ascii-and-line-feeds", () -> tag + " " + era + " " + file.getKey() + " has a carriage return, a non-ASCII character or no final line feed");
            }
        }
    }

    // --- S3: the lock ------------------------------------------------------------------------------------------------

    private void stableCodes() {
        Program p1 = Programs.band();
        Program p2 = Programs.bandExtended();
        String item = p1.item();
        Lock l1 = lockFor(p1);
        Compilation.Compiled first = compiled(p1, l1, LEGACY, "S3 first");
        if (first != null) {
            for (Plan.Entry e : first.plan().entries()) {
                Integer locked = l1.codeOf(item, e.key());
                ok(locked != null && locked.intValue() == e.code(), "S3:existing-codes-kept",
                        () -> e.key() + " has code " + e.code() + " in the plan and " + locked + " in the lock");
            }
        }
        // a new selection: the lock lacks it, and the diagnostic gives the exact line to add
        Compilation missing;
        try {
            missing = compiler.compile(p2, l1, catalog, LEGACY);
        } catch (RuntimeException e) {
            missing = null;
            c.fail("S3:missing-lock-lists-lines (threw " + e + ")");
        }
        Lock l2 = l1;
        Compilation.Compiled second = null;
        if (missing instanceof Compilation.Rejected r) {
            List<String> lines = new ArrayList<>();
            boolean onlyMissing = true;
            for (Diagnostic d : r.diagnostics()) {
                onlyMissing &= d.kind() == Diagnostic.Kind.LOCK_MISSING;
                lines.addAll(d.lockLines());
            }
            ok(onlyMissing && lines.size() == 1 && lines.get(0).contains("status_tool_aaa_prime") && lines.get(0).contains("\"code\": " + l1.next(item)),
                    "S3:missing-lock-lists-lines", () -> "the refusal gave " + r.diagnostics());
            try {
                l2 = l1.apply(item, lines);
                Compilation done = compiler.compile(p2, l2, catalog, LEGACY);
                ok(done instanceof Compilation.Compiled, "S3:lines-complete-the-lock", () -> "still refused with the lines added: " + done);
                if (done instanceof Compilation.Compiled good) {
                    second = good;
                }
            } catch (RuntimeException e) {
                c.fail("S3:lines-complete-the-lock (" + e + ")");
            }
        } else if (missing instanceof Compilation.Compiled good) {
            c.fail("S3:missing-lock-lists-lines (compiled against a lock that lacks a selection, with codes " + good.plan().entries() + ")");
            second = good;
        }
        if (second != null) {
            for (Plan.Entry e : second.plan().entries()) {
                Integer before = l1.codeOf(item, e.key());
                if (before != null) {
                    ok(before.intValue() == e.code(), "S3:existing-codes-kept",
                            () -> "after a case was added, " + e.key() + " went from code " + before + " to " + e.code());
                } else {
                    ok(e.code() == l1.next(item), "S3:new-selections-append",
                            () -> "the new selection " + e.key() + " took code " + e.code() + ", not the next, " + l1.next(item));
                }
            }
        }
        // a selection the program stops producing keeps its code, and the code draws the base
        Program p3 = Programs.bandReduced();
        Compilation.Compiled reduced = compiled(p3, l1, LEGACY, "S2 retired");
        Compilation.Compiled reducedModern = compiled(p3, l1, MODERN, "S2 retired");
        if (reduced != null && reducedModern != null) {
            List<String> live = new ArrayList<>();
            for (Plan.Entry e : reduced.plan().entries()) {
                live.add(e.key());
            }
            String legacy = reduced.artefacts().get(Layout.model(item));
            String modern = reducedModern.artefacts().get(Layout.definition(item));
            int retired = 0;
            for (Lock.Entry e : l1.entries(item)) {
                if (live.contains(e.key())) {
                    continue;
                }
                retired++;
                try {
                    String viaCode = Simulators.legacy(legacy, new Selector.Code(e.code()));
                    String viaKey = Simulators.modern(modern, new Selector.Key(e.key()));
                    ok(viaCode.equals(p3.base()) && viaKey.equals(p3.base()), "S2:retired-code-draws-base",
                            () -> e.key() + " (code " + e.code() + ") draws " + viaCode + " / " + viaKey + " once no case produces it");
                } catch (RuntimeException ex) {
                    c.fail("S2:retired-code-draws-base (" + e.key() + ": " + ex.getMessage() + ")");
                }
            }
            c.that(retired > 0, "S2:retired-code-draws-base (the harness retired nothing)");
        }
    }

    // --- S5: the projection ------------------------------------------------------------------------------------------

    private void projection() {
        String leaked = "\"left\": {\"component\": \"example:server_state\", \"field\": \"deadline\", \"domain\": {\"int\": [0, 3]}}";
        String cases = "{\"when\": {\"left\": {\"min\": 2}}, \"model\": \"example:item/late\"}";
        Program secret = Programs.mini("example:probe", "example:item/probe", leaked, cases);
        expectRejected(secret, Diagnostic.Kind.SERVER_PRIVATE, "S5:server-private-rejected");
        // an input declared but never read is still an input over a component the client does not have
        Program unread = Programs.mini("example:probe", "example:item/probe", leaked
                + ", \"mood\": {\"component\": \"example:spool\", \"field\": \"thread\", \"domain\": {\"enum\": [\"a\", \"b\"]}}",
                "{\"when\": {\"mood\": \"a\"}, \"model\": \"example:item/a\"}");
        expectRejected(unread, Diagnostic.Kind.SERVER_PRIVATE, "S5:server-private-rejected");

        expectRejected(Programs.mini("example:probe", "example:item/probe",
                "\"x\": {\"component\": \"example:nope\", \"field\": \"f\", \"domain\": \"bool\"}", "{\"when\": {\"x\": true}, \"model\": \"example:item/a\"}"),
                Diagnostic.Kind.CATALOG, "S5:unknown-component-rejected");
        expectRejected(Programs.mini("example:probe", "example:item/probe",
                "\"x\": {\"component\": \"example:tool_state\", \"field\": \"nope\", \"domain\": \"bool\"}", "{\"when\": {\"x\": true}, \"model\": \"example:item/a\"}"),
                Diagnostic.Kind.CATALOG, "S5:unknown-component-rejected");
        expectRejected(Programs.mini("example:probe", "example:item/probe",
                "\"x\": {\"component\": \"example:tool_state\", \"field\": \"seams\", \"domain\": \"bool\"}", "{\"when\": {\"x\": true}, \"model\": \"example:item/a\"}"),
                Diagnostic.Kind.TYPE, "S5:type-mismatch-rejected");
        expectRejected(Programs.mini("example:probe", "example:item/probe",
                "\"x\": {\"component\": \"example:tool_state\", \"domain\": \"present\"}", "{\"when\": {\"x\": true}, \"model\": \"example:item/a\"}"),
                Diagnostic.Kind.TYPE, "S5:type-mismatch-rejected");
        expectRejected(Programs.mini("example:probe", "example:item/probe",
                "\"x\": {\"component\": \"minecraft:glider\", \"field\": \"f\", \"domain\": \"bool\"}", "{\"when\": {\"x\": true}, \"model\": \"example:item/a\"}"),
                Diagnostic.Kind.TYPE, "S5:type-mismatch-rejected");

        // the catalog is held to the registry it describes
        record Keep(int seams, boolean broken) {
        }
        RecordCodec.Field<Keep, Integer> seams = RecordCodec.field("seams", ComponentCodec.INT, Keep::seams);
        RecordCodec.Field<Keep, Boolean> broken = RecordCodec.field("broken", ComponentCodec.BOOLEAN, Keep::broken);
        RecordCodec<Keep> codec = RecordCodec.of(r -> new Keep(r.get(seams), r.get(broken)), seams, broken);
        ComponentId id = ComponentId.of("example:tool_state");
        ComponentDefinition<Keep> visible = codec.definition(id, new Keep(0, false));
        String claim = "{\"schema\": 1, \"components\": {\"example:tool_state\": {\"networkSynced\": true, \"fields\": {\"seams\": \"int\", \"broken\": \"bool\"}}}}";
        ComponentCatalog claimed = ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(claim)).value();
        ComponentRegistry honest = ComponentRegistry.builder().register(visible).build();
        ComponentRegistry hidden = ComponentRegistry.builder().register(visible.withNetworkSynced(false)).build();
        ok(claimed.disagreements(honest).isEmpty(), "S5:catalog-agrees-with-registry", () -> "an honest catalog disagrees: " + claimed.disagreements(honest));
        List<Diagnostic> lies = claimed.disagreements(hidden);
        ok(lies.size() == 1 && lies.get(0).kind() == Diagnostic.Kind.SERVER_PRIVATE, "S5:catalog-agrees-with-registry",
                () -> "a catalog calling a server-private component visible gave " + lies);
        ComponentCatalog extra = ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(claim.replace("\"broken\": \"bool\"}", "\"broken\": \"bool\", \"gone\": \"int\"}"))).value();
        ok(extra.disagreements(honest).size() == 1, "S5:catalog-agrees-with-registry", () -> "a field the codec lacks gave " + extra.disagreements(honest));
    }

    private void expectRejected(Program p, Diagnostic.Kind kind, String label) {
        Compilation result;
        try {
            result = compiler.compile(p, lockFor(p), catalog, MODERN);
        } catch (RuntimeException e) {
            c.fail(label + " (threw " + e + ")");
            return;
        }
        boolean rejected = result instanceof Compilation.Rejected r && r.diagnostics().stream().anyMatch(d -> d.kind() == kind);
        ok(rejected, label, () -> "expected a " + kind + " refusal, got " + brief(result));
    }

    private static String brief(Compilation result) {
        return result instanceof Compilation.Rejected r ? r.diagnostics().toString() : "a compilation with " + ((Compilation.Compiled) result).artefacts().keySet();
    }

    // --- S7: bounded -------------------------------------------------------------------------------------------------

    private void bounded() {
        String huge = "\"a\": {\"component\": \"example:tool_state\", \"field\": \"seams\", \"domain\": {\"int\": [0, 2000000000]}},"
                + "\"b\": {\"component\": \"example:tool_state\", \"field\": \"cracks\", \"domain\": {\"int\": [0, 2000000000]}}";
        Program big = Programs.mini("example:probe", "example:item/probe", huge,
                "{\"when\": {\"a\": {\"min\": 1}, \"b\": {\"min\": 1}}, \"model\": \"example:item/a\"}");
        Compilation result = compiler.compile(big, Lock.empty(), catalog, MODERN);
        ok(result instanceof Compilation.Rejected r && r.diagnostics().size() == 1 && r.diagnostics().get(0).kind() == Diagnostic.Kind.TOO_LARGE,
                "S7:product-over-cap-rejected-before-enumeration", () -> "a four-quintillion-tuple product gave " + result);

        int cap = compiler.maxTuples();
        String ranged = "\"n\": {\"component\": \"example:tool_state\", \"field\": \"seams\", \"domain\": {\"int\": [0, " + (cap - 1) + "]}}";
        String one = "{\"when\": {\"n\": 7}, \"model\": \"example:item/seven\"}";
        Program atCap = Programs.mini("example:probe", "example:item/probe", ranged, one);
        Compilation ok = compiler.compile(atCap, lockFor(atCap), catalog, MODERN);
        ok(ok instanceof Compilation.Compiled, "S7:product-at-cap-accepted", () -> "exactly " + cap + " tuples gave " + ok);
        Program over = Programs.mini("example:probe", "example:item/probe", ranged.replace("[0, " + (cap - 1) + "]", "[0, " + cap + "]"), one);
        Compilation refused = compiler.compile(over, lockFor(over), catalog, MODERN);
        ok(refused instanceof Compilation.Rejected r && r.diagnostics().get(0).kind() == Diagnostic.Kind.TOO_LARGE,
                "S7:product-over-cap-rejected-before-enumeration", () -> (cap + 1) + " tuples gave " + refused);
    }

    // --- S6: determinism ---------------------------------------------------------------------------------------------

    private void determinism() {
        Program p = Programs.band();
        Lock lock = lockFor(p);
        Compilation.Compiled base = compiled(p, lock, MODERN, "S6");
        Compilation.Compiled baseLegacy = compiled(p, lock, LEGACY, "S6");
        Compilation.Compiled reordered = compiled(Programs.bandReordered(), lock, MODERN, "S6 reordered");
        Compilation.Compiled reorderedLegacy = compiled(Programs.bandReordered(), lock, LEGACY, "S6 reordered");
        if (base != null && reordered != null && baseLegacy != null && reorderedLegacy != null) {
            ok(base.artefacts().equals(reordered.artefacts()) && baseLegacy.artefacts().equals(reorderedLegacy.artefacts()),
                    "S6:declaration-order-independent", () -> "a program written with its inputs in another order compiles to other bytes");
        }
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Compilation.Compiled turkish = compiled(p, lock, MODERN, "S6 locale");
            Compilation.Compiled turkishLegacy = compiled(p, lock, LEGACY, "S6 locale");
            if (base != null && turkish != null && baseLegacy != null && turkishLegacy != null) {
                ok(base.artefacts().equals(turkish.artefacts()) && baseLegacy.artefacts().equals(turkishLegacy.artefacts()),
                        "S6:locale-independent", () -> "the bytes change with the default locale");
            }
        } finally {
            Locale.setDefault(before);
        }
        // the lock file is canonical too
        ok(Lock.parse(lock.toJson()) instanceof Result.Ok<Lock> good && good.value().toJson().equals(lock.toJson()), "S6:canonical-json",
                () -> "the lock does not round trip");
    }

    // --- evaluation: what the cases mean -----------------------------------------------------------------------------

    private void evaluation() {
        Program spool = Programs.spool();
        Map<String, Object> tuple = new LinkedHashMap<>();
        tuple.put("lit", Boolean.TRUE);
        tuple.put("thread", "taut");
        ok(Evaluator.evaluate(spool, tuple).equals("example:item/spool_lit_taut"), "S1:first-match-wins",
                () -> "a stack matching two cases drew " + Evaluator.evaluate(spool, tuple));
        tuple.put("lit", Boolean.FALSE);
        ok(Evaluator.evaluate(spool, tuple).equals("example:item/spool_taut"), "S1:first-match-wins", () -> "the second case was not reached");
        tuple.put("thread", "none");
        ok(Evaluator.evaluate(spool, tuple).equals(spool.base()), "S1:base-when-no-case", () -> "no case applies, and " + Evaluator.evaluate(spool, tuple) + " was drawn");

        ok(spool.pruned().inputs().size() == 2 && spool.input("tension") != null && !spool.readNames().contains("tension"),
                "S1:unread-input-dropped", () -> "the input no case reads stayed in the plan: " + spool.pruned().inputs());
        Compilation.Compiled done = compiled(spool, lockFor(spool), LEGACY, "unread");
        if (done != null) {
            ok(done.plan().program().inputs().size() == 2, "S1:unread-input-dropped", () -> "the plan keeps inputs " + done.plan().program().inputs());
            Programs.MapView stale = new Programs.MapView().with("example:spool", "thread", "taut").with("example:spool", "tension", Long.valueOf(999999));
            ok(done.plan().select(stale).model().equals("example:item/spool_taut"), "S1:unread-input-dropped", () -> "an unread input changed the selection");
            Programs.MapView bogus = new Programs.MapView().with("example:spool", "thread", "frayed");
            Selection out = done.plan().select(bogus);
            ok(out.isBase() && !out.inDomain(), "S1:out-of-domain-draws-base", () -> "a state outside the domain drew " + out);
            Programs.MapView wrongKind = new Programs.MapView().with("example:spool", "thread", Long.valueOf(3));
            Selection kind = done.plan().select(wrongKind);
            ok(kind.isBase() && !kind.inDomain(), "S1:out-of-domain-draws-base", () -> "a value of the wrong kind drew " + kind);
            Selection nothing = done.plan().select(new Programs.MapView());
            ok(nothing.isBase() && nothing.inDomain(), "S1:absent-component-reads-default", () -> "a stack with no components drew " + nothing);
        }
    }

    // --- the target --------------------------------------------------------------------------------------------------

    private void realization() {
        Program p = Programs.band();
        Compilation none = compiler.compile(p, lockFor(p), catalog, new Target("1.12.2", Realization.NONE));
        ok(none instanceof Compilation.Rejected r && r.diagnostics().get(0).kind() == Diagnostic.Kind.NO_REALIZATION
                && r.diagnostics().get(0).message().contains("1.12.2"), "R1:no-realization-rejected", () -> "a version with no mechanism gave " + none);
        Program clash = Programs.mini("example:probe", "example:item/probe_base", "", "{\"model\": \"example:item/probe_lit\"}");
        Program own = Programs.mini("example:probe", "example:item/probe_base", "", "{\"model\": \"example:item/probe\"}");
        Compilation legacy = compiler.compile(own, lockFor(own), catalog, LEGACY);
        ok(legacy instanceof Compilation.Rejected r && r.diagnostics().get(0).kind() == Diagnostic.Kind.MODEL_CLASH, "R2:model-clash-rejected",
                () -> "overrides on a model that is also drawn gave " + legacy);
        ok(compiler.compile(clash, lockFor(clash), catalog, LEGACY) instanceof Compilation.Compiled, "R2:model-clash-rejected",
                () -> "a program with no clash was refused");
    }

    // --- the program itself ------------------------------------------------------------------------------------------

    private void wellFormed() {
        String ok = "{\"item\": \"a:b\", \"base\": \"a:item/b\", \"inputs\": {\"n\": {\"component\": \"a:c\", \"field\": \"f\", \"domain\": {\"int\": [0, 3]}}}, \"cases\": []}";
        Object[][] bad = {
                {"not json", Diagnostic.Kind.SYNTAX},
                {ok.replace("\"cases\": []", "\"cases\": [], \"extra\": 1"), Diagnostic.Kind.SCHEMA},
                {ok.replace("{\"int\": [0, 3]}", "\"float\""), Diagnostic.Kind.SCHEMA},
                {ok.replace("\"cases\": []", "\"cases\": [{\"when\": {\"m\": 1}, \"model\": \"a:item/x\"}]"), Diagnostic.Kind.SCHEMA},
                {ok.replace("\"cases\": []", "\"cases\": [{\"when\": {\"n\": 9}, \"model\": \"a:item/x\"}]"), Diagnostic.Kind.DOMAIN},
                {ok.replace("\"cases\": []", "\"cases\": [{\"when\": {\"n\": {\"min\": 7}}, \"model\": \"a:item/x\"}]"), Diagnostic.Kind.DOMAIN},
                {ok.replace("\"cases\": []", "\"cases\": [{\"when\": {\"n\": true}, \"model\": \"a:item/x\"}]"), Diagnostic.Kind.TYPE},
                {ok.replace("\"cases\": []", "\"cases\": [{\"model\": \"a:item/x_{zed}\"}]"), Diagnostic.Kind.TEMPLATE},
                {ok.replace("\"cases\": []", "\"cases\": [{\"model\": \"Bad Ref\"}]"), Diagnostic.Kind.TEMPLATE},
                {ok.replace("\"cases\": []", "\"cases\": [{\"model\": \"a:item/x_{n\"}]"), Diagnostic.Kind.TEMPLATE},
                {ok.replace("{\"int\": [0, 3]}", "{\"enum\": [\"a\", \"b\"]}").replace("\"cases\": []", "\"cases\": [{\"when\": {\"n\": {\"min\": 1}}, \"model\": \"a:item/x\"}]"), Diagnostic.Kind.TYPE},
                {ok.replace("\"domain\"", "\"default\": 9, \"domain\""), Diagnostic.Kind.DOMAIN},
                {ok.replace("{\"int\": [0, 3]}", "{\"enum\": [\"Up\"]}"), Diagnostic.Kind.DOMAIN},
                {ok.replace("{\"int\": [0, 3]}", "{\"int\": [3, 0]}"), Diagnostic.Kind.DOMAIN},
                {ok.replace("{\"int\": [0, 3]}", "\"present\""), Diagnostic.Kind.SCHEMA},
                {ok.replace("\"item\": \"a:b\",", "\"item\": \"a:b\", \"item\": \"a:c\","), Diagnostic.Kind.SYNTAX},
                {ok.replace("[0, 3]", "[0, 3.5]"), Diagnostic.Kind.SYNTAX},
                {ok.replace("\"base\": \"a:item/b\"", "\"base\": 4"), Diagnostic.Kind.SCHEMA},
        };
        ok(Program.parse(ok) instanceof Result.Ok, "P1:well-formed-program-accepted", () -> "the sound program was refused: " + Program.parse(ok));
        for (Object[] one : bad) {
            Result<Program> r = Program.parse((String) one[0]);
            boolean caught = r instanceof Result.Failed<Program> f && f.diagnostics().stream().anyMatch(d -> d.kind() == one[1]);
            ok(caught, "P1:malformed-program-rejected", () -> "expected " + one[1] + " for " + one[0] + ", got " + r);
        }
        // a compiler checks the program it is handed, whoever built it
        Program built = new Program("a:b", "a:item/b", Collections.<Input>emptyList(),
                Collections.singletonList(new Case(Collections.singletonMap("ghost", new Condition.Equals(Boolean.TRUE)), "a:item/x")));
        Compilation result = compiler.compile(built, Lock.empty(), catalog, MODERN);
        ok(result instanceof Compilation.Rejected, "P1:compiler-rechecks-program", () -> "a guard on an undeclared input compiled: " + result);
        Lock again = Lock.parse(Lock.empty().plus("a:b", Lock.empty().allocate("a:b", List.of("a:item/x", "a:item/y"))).toJson()) instanceof Result.Ok<Lock> good
                ? good.value() : null;
        ok(again != null && again.codeOf("a:b", "a:item/y") == 2, "L1:lock-round-trips", () -> "a lock did not read back");
        ok(Lock.parse("{\"schema\": 1, \"items\": {\"a:b\": [{\"code\": 1, \"key\": \"a:item/x\"}, {\"code\": 1, \"key\": \"a:item/y\"}]}}") instanceof Result.Failed,
                "L1:lock-rejects-reused-code", () -> "two selections with one code were accepted");
        ok(Lock.parse("{\"schema\": 1, \"items\": {\"a:b\": [{\"code\": 1, \"key\": \"a:item/x\"}, {\"code\": 2, \"key\": \"a:item/x\"}]}}") instanceof Result.Failed,
                "L1:lock-rejects-reused-key", () -> "one selection with two codes was accepted");
        ok(Lock.parse("{\"schema\": 1, \"items\": {\"a:b\": [{\"code\": 0, \"key\": \"a:item/x\"}]}}") instanceof Result.Failed,
                "L1:lock-rejects-code-zero", () -> "code 0 (no custom model data) was accepted");
    }

    // --- an adapter over real components -----------------------------------------------------------------------------

    private void adapter() {
        record Keep(int seams, boolean broken) {
        }
        RecordCodec.Field<Keep, Integer> seams = RecordCodec.field("seams", ComponentCodec.INT, Keep::seams);
        RecordCodec.Field<Keep, Boolean> broken = RecordCodec.field("broken", ComponentCodec.BOOLEAN, Keep::broken);
        RecordCodec<Keep> codec = RecordCodec.of(r -> new Keep(r.get(seams), r.get(broken)), seams, broken);
        ComponentDefinition<Keep> definition = codec.definition(ComponentId.of("example:tool_state"), new Keep(0, false));
        ComponentRegistry registry = ComponentRegistry.builder().register(definition).build();
        Program p = Programs.mini("example:keep", "example:item/keep_base",
                "\"seams\": {\"component\": \"example:tool_state\", \"field\": \"seams\", \"domain\": {\"int\": [0, 3]}},"
                        + "\"broken\": {\"component\": \"example:tool_state\", \"field\": \"broken\", \"domain\": \"bool\"}",
                "{\"when\": {\"broken\": true}, \"model\": \"example:item/keep_broken\"},"
                        + "{\"when\": {\"seams\": {\"min\": 1}}, \"model\": \"example:item/keep_{seams}\"}");
        Compilation.Compiled done = compiled(p, lockFor(p), LEGACY, "adapter");
        if (done == null) {
            return;
        }
        for (int s = 0; s <= 3; s++) {
            for (boolean b : new boolean[] {false, true}) {
                CarrierSnapshot snapshot = CarrierSnapshot.builder().with(definition, new Keep(s, b)).build();
                Selection sel = done.plan().select(new CarrierView(snapshot, registry, catalog));
                Map<String, Object> tuple = new LinkedHashMap<>();
                tuple.put("broken", Boolean.valueOf(b));
                tuple.put("seams", Long.valueOf(s));
                String expected = Evaluator.evaluate(p, tuple);
                final int seamsNow = s;
                final boolean brokenNow = b;
                ok(sel.model().equals(expected) && sel.inDomain(), "S2:carrier-view-agrees",
                        () -> "seams " + seamsNow + ", broken " + brokenNow + " read from a carrier snapshot drew " + sel + ", not " + expected);
            }
        }
        Selection plain = done.plan().select(new CarrierView(CarrierSnapshot.empty(), registry, catalog));
        ok(plain.isBase() && plain.inDomain(), "S2:carrier-view-agrees", () -> "a snapshot carrying nothing read as " + plain);
    }

    // --- the build tool, over text -----------------------------------------------------------------------------------

    private void tool() {
        Map<String, String> programs = new TreeMap<>();
        programs.put("band.json", bandJson());
        programs.put("spool.json", spoolJson());
        try {
            String empty = Lock.empty().toJson();
            Map<?, ?> first = report(SigilTool.compile("1.21.4", "item-definition", programs, Programs.CATALOG_JSON, empty));
            ok(Boolean.FALSE.equals(first.get("ok")) && first.toString().contains("LOCK_MISSING"), "T1:tool-reports-missing-lock",
                    () -> "a build against an empty lock reported " + first);
            Map<?, ?> locked = report(SigilTool.lock(programs, Programs.CATALOG_JSON, empty));
            ok(Boolean.TRUE.equals(locked.get("ok")) && locked.get("lock") instanceof String, "T1:tool-completes-the-lock", () -> "the lock task reported " + locked);
            String lock = (String) locked.get("lock");
            Map<?, ?> again = report(SigilTool.lock(programs, Programs.CATALOG_JSON, lock));
            ok(Boolean.TRUE.equals(again.get("ok")) && lock.equals(again.get("lock")) && ((List<?>) again.get("added")).isEmpty(), "T1:tool-lock-is-idempotent",
                    () -> "completing a complete lock changed it: " + again);
            for (String[] target : new String[][] {{"1.21.4", "item-definition"}, {"1.20.1", "model-overrides"}}) {
                Map<?, ?> built = report(SigilTool.compile(target[0], target[1], programs, Programs.CATALOG_JSON, lock));
                Map<?, ?> files = (Map<?, ?>) built.get("files");
                ok(Boolean.TRUE.equals(built.get("ok")) && files.size() == 4, "T1:tool-compiles", () -> target[1] + ": " + built);
                String repeat = SigilTool.compile(target[0], target[1], programs, Programs.CATALOG_JSON, lock);
                ok(repeat.equals(SigilTool.compile(target[0], target[1], programs, Programs.CATALOG_JSON, lock)), "T1:tool-is-deterministic", () -> "two runs differ");
            }
            Map<?, ?> none = report(SigilTool.compile("1.12.2", "none", programs, Programs.CATALOG_JSON, lock));
            ok(Boolean.FALSE.equals(none.get("ok")) && none.toString().contains("NO_REALIZATION"), "R1:no-realization-rejected", () -> "none gave " + none);
            programs.put("clone.json", bandJson());
            Map<?, ?> twice = report(SigilTool.compile("1.21.4", "item-definition", programs, Programs.CATALOG_JSON, lock));
            ok(Boolean.FALSE.equals(twice.get("ok")), "T1:tool-one-program-per-item", () -> "two programs for one item gave " + twice);
        } catch (RuntimeException e) {
            c.fail("T1:tool-runs (threw " + e + ")");
        }
    }

    private static String bandJson() {
        return Json.write(Programs.band().toTree());
    }

    private static String spoolJson() {
        return Json.write(Programs.spool().toTree());
    }

    private static Map<?, ?> report(String text) {
        return (Map<?, ?>) Json.parse(text);
    }

    // --- seeded programs ---------------------------------------------------------------------------------------------

    private void properties() {
        for (int n = 0; n < 40; n++) {
            final int seed = n;
            Seeded r = new Seeded(0x51617L + seed);
            List<ComponentCatalog.Component> components = new ArrayList<>();
            Program p = random(r, components);
            List<String> problems = new ArrayList<>();
            for (Diagnostic d : p.check()) {
                problems.add(d.toString());
            }
            if (!problems.isEmpty()) {
                c.fail("harness:random-program-is-sound (seed " + seed + ": " + problems + ")");
                continue;
            }
            ComponentCatalog own = ComponentCatalog.of(components);
            Lock lock = lockFor(p);
            for (Target target : new Target[] {MODERN, LEGACY}) {
                Compilation result = compiler.compile(p, lock, own, target);
                if (result instanceof Compilation.Rejected rej) {
                    c.fail("S1:compiles (seed " + seed + ": " + rej.diagnostics() + ")");
                    continue;
                }
                Compilation.Compiled done = (Compilation.Compiled) result;
                boolean isModern = target == MODERN;
                String artefact = done.artefacts().get(isModern ? Layout.definition(p.item()) : Layout.model(p.item()));
                for (Map<String, Object> tuple : Evaluator.tuples(p.pruned().inputs())) {
                    String oracle = Evaluator.evaluate(p, tuple);
                    Selection sel = done.plan().select(tuple);
                    ok(sel.model().equals(oracle), "S1:totality-agrees-with-evaluator", () -> "seed " + seed + " " + tuple + ": " + oracle + " vs " + sel.model());
                    String drawn = isModern ? Simulators.modern(artefact, done.plan().selectorOf(sel)) : Simulators.legacy(artefact, done.plan().selectorOf(sel));
                    ok(oracle.equals(drawn), isModern ? "S2:modern-draws-evaluated-model" : "S2:legacy-draws-evaluated-model",
                            () -> "seed " + seed + " " + tuple + ": " + oracle + " vs " + drawn);
                }
                if (!isModern) {
                    List<Long> predicates = Simulators.predicates(artefact);
                    boolean ascending = true;
                    for (int i = 1; i < predicates.size(); i++) {
                        ascending &= predicates.get(i) > predicates.get(i - 1);
                    }
                    ok(ascending, "S4:overrides-strictly-ascending", () -> "seed " + seed + ": " + predicates);
                }
                Compilation.Compiled again = (Compilation.Compiled) compiler.compile(p, lock, own, target);
                ok(again.artefacts().equals(done.artefacts()), "S6:repeat-identical", () -> "seed " + seed);
            }
        }
    }

    /** A random sound program over inputs of every kind: some unread, some guarded, some templated into a model. */
    private static Program random(Seeded r, List<ComponentCatalog.Component> components) {
        int count = r.nextInt(1, 5);
        List<Input> inputs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String component = "rnd:c" + i;
            int kind = r.nextInt(4);
            Domain domain;
            if (kind == 0) {
                domain = new Domain.Flag();
                components.add(ComponentCatalog.Component.record(component, true, Collections.singletonMap("f", mycelium.item_presentation.v1.model.FieldKind.BOOL)));
            } else if (kind == 1) {
                domain = new Domain.Present();
                components.add(ComponentCatalog.Component.unit(component, true));
            } else if (kind == 2) {
                long min = r.nextInt(-3, 3);
                domain = new Domain.IntRange(min, min + r.nextInt(1, 5));
                components.add(ComponentCatalog.Component.record(component, true, Collections.singletonMap("f", mycelium.item_presentation.v1.model.FieldKind.INT)));
            } else {
                List<String> pool = new ArrayList<>(List.of("a", "b", "c", "d"));
                int n = r.nextInt(2, 5);
                domain = new Domain.Choice(pool.subList(0, n));
                components.add(ComponentCatalog.Component.record(component, true, Collections.singletonMap("f", mycelium.item_presentation.v1.model.FieldKind.TEXT)));
            }
            inputs.add(new Input("in" + i, component, kind == 1 ? "" : "f", domain, null));
        }
        List<Case> cases = new ArrayList<>();
        int caseCount = r.nextInt(0, 7);
        for (int k = 0; k < caseCount; k++) {
            Map<String, Condition> when = new LinkedHashMap<>();
            for (Input input : inputs) {
                if (r.nextBoolean()) {
                    when.put(input.name(), condition(r, input.domain()));
                }
            }
            String model;
            if (r.nextBoolean()) {
                model = "rnd:item/m" + r.nextInt(3);
            } else {
                model = "rnd:item/t" + k + "_{" + inputs.get(r.nextInt(inputs.size())).name() + "}";
            }
            cases.add(new Case(when, model));
        }
        return new Program("rnd:thing", "rnd:item/base", inputs, cases);
    }

    private static Condition condition(Seeded r, Domain domain) {
        List<Object> values = domain.values();
        int shape = r.nextInt(3);
        if (shape == 2 && domain instanceof Domain.IntRange range) {
            long lo = range.min() + r.nextInt((int) range.size());
            long hi = lo + r.nextInt((int) (range.max() - lo + 1));
            return r.nextBoolean() ? new Condition.Within(Long.valueOf(lo), Long.valueOf(hi)) : new Condition.Within(Long.valueOf(lo), null);
        }
        if (shape == 1 && values.size() > 1) {
            int a = r.nextInt(values.size());
            int b = (a + 1 + r.nextInt(values.size() - 1)) % values.size();
            return new Condition.AnyOf(List.of(values.get(a), values.get(b)));
        }
        return new Condition.Equals(values.get(r.nextInt(values.size())));
    }
}
