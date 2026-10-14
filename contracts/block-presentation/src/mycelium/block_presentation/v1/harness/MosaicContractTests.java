package mycelium.block_presentation.v1.harness;

import mycelium.block_presentation.v1.harness.Mosaics.MapView;
import mycelium.block_presentation.v1.model.Assembler;
import mycelium.block_presentation.v1.model.Assembly;
import mycelium.block_presentation.v1.model.BlockRealization;
import mycelium.block_presentation.v1.model.BlockTarget;
import mycelium.block_presentation.v1.model.Layout;
import mycelium.block_presentation.v1.model.Mosaic;
import mycelium.block_presentation.v1.model.MosaicCompilation;
import mycelium.block_presentation.v1.model.MosaicCompiler;
import mycelium.block_presentation.v1.model.MosaicPlan;
import mycelium.block_presentation.v1.model.MosaicTool;
import mycelium.block_presentation.v1.model.Part;
import mycelium.block_presentation.v1.model.Placed;
import mycelium.block_presentation.v1.model.ReferenceMosaicPlan;
import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Diagnostic;
import mycelium.item_presentation.v1.model.Domain;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Json;
import mycelium.item_presentation.v1.model.Result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The laws of {@code block.presentation@1} (M1 to M7), held against one compiler. A law's label is its surface: the rejection matrix
 * names the label each broken compiler must fail.
 */
public final class MosaicContractTests {
    static final BlockTarget MODERN = new BlockTarget("modern", BlockRealization.DYNAMIC_MODEL);

    private final MosaicCompiler compiler;
    private final Check c = new Check();
    private final ComponentCatalog catalog = Mosaics.catalog();

    public MosaicContractTests(MosaicCompiler compiler) {
        this.compiler = compiler;
    }

    public Check run() {
        Mosaic display = Mosaics.parse(Mosaics.DISPLAY);
        MosaicCompilation.Compiled displayPlan = compiled(display);
        assembly(display, displayPlan);
        independence(display, displayPlan);
        manifest(display, displayPlan);
        order(display, displayPlan);
        Mosaic panel = Mosaics.parse(Mosaics.panel(12));
        MosaicCompilation.Compiled panelPlan = compiled(panel);
        assembly(panel, panelPlan);
        manifest(panel, panelPlan);
        degenerate();
        projection();
        bounded();
        determinism();
        wellFormed();
        realization();
        tool();
        properties();
        return c;
    }

    private void ok(boolean cond, String label, Supplier<String> detail) {
        c.that(cond, cond ? label : label + " " + detail.get());
    }

    private MosaicCompilation.Compiled compiled(Mosaic m) {
        MosaicCompilation r = compiler.compile(m, catalog, MODERN);
        if (r instanceof MosaicCompilation.Compiled done) {
            return done;
        }
        c.fail("P1:sound-mosaic-compiles " + m.block() + " " + ((MosaicCompilation.Rejected) r).diagnostics());
        throw new IllegalStateException("the compiler refused a sound mosaic");
    }

    // --- independent oracle ------------------------------------------------------------------------------------------

    /** What a state draws, worked out here with nothing but the parts' own guards and a plain text replacement. */
    private static List<Placed> oracle(Mosaic m, Map<String, Object> tuple) {
        List<Placed> out = new ArrayList<>();
        for (int n = 0; n < m.parts().size(); n++) {
            Part p = m.parts().get(n);
            boolean holds = true;
            for (Map.Entry<String, mycelium.item_presentation.v1.model.Condition> g : p.when().entrySet()) {
                holds &= g.getValue().test(tuple.get(g.getKey()));
            }
            if (holds) {
                String model = p.model();
                for (Map.Entry<String, Object> e : tuple.entrySet()) {
                    model = model.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
                }
                out.add(new Placed(model, p.turn(), n));
            }
        }
        return out;
    }

    private static String modelAt(List<Placed> placed, int n) {
        return n < placed.size() ? placed.get(n).model() : "";
    }

    private static List<Map<String, Object>> everyTuple(Mosaic m) {
        return Evaluator.tuples(m.readInputs());
    }

    // --- M1, M2, M3, M4 ------------------------------------------------------------------------------------------------

    private void assembly(Mosaic m, MosaicCompilation.Compiled done) {
        MosaicPlan plan = done.plan();
        String name = m.block();
        List<Map<String, Object>> tuples = everyTuple(plan.mosaic());
        int bad = 0;
        int viewBad = 0;
        int resourceBad = 0;
        String first = "";
        MosaicPlan back = readBack(done);
        for (Map<String, Object> tuple : tuples) {
            List<Placed> want = oracle(m, tuple);
            List<Placed> got = plan.assemble(tuple);
            if (!want.equals(got) && bad++ == 0) {
                first = tuple + ": " + got + " vs " + want;
            }
            Assembly viewed = plan.assemble(MapView.of(plan.mosaic(), tuple));
            if (!want.equals(viewed.placed()) || !viewed.inDomain()) {
                viewBad++;
            }
            if (back != null && !want.equals(back.assemble(tuple))) {
                resourceBad++;
            }
        }
        final String shown = first;
        final int badCount = bad;
        ok(bad == 0, "M1:totality-agrees-with-evaluator " + name, () -> badCount + " of " + tuples.size() + " states differ, first " + shown);
        final int viewCount = viewBad;
        ok(viewBad == 0, "M2:adapter-reading-agrees " + name, () -> viewCount + " states read through a view differ");
        final int resourceCount = resourceBad;
        ok(back != null && resourceBad == 0, "M2:plan-resource-agrees " + name, () -> resourceCount + " states differ after the resource is read back");
        // which parts are drawn is exactly which guards hold, and the turn is the part's own, whatever the compiler did to the plan
        int missing = 0;
        int extra = 0;
        int wrongTurn = 0;
        for (Map<String, Object> tuple : tuples) {
            List<Integer> holding = new ArrayList<>();
            for (int n = 0; n < m.parts().size(); n++) {
                boolean holds = true;
                for (Map.Entry<String, mycelium.item_presentation.v1.model.Condition> g : m.parts().get(n).when().entrySet()) {
                    holds &= g.getValue().test(tuple.get(g.getKey()));
                }
                if (holds) {
                    holding.add(n);
                }
            }
            List<Integer> drawn = new ArrayList<>();
            for (Placed p : plan.assemble(tuple)) {
                drawn.add(p.part());
                if (p.turn() != m.parts().get(p.part()).turn()) {
                    wrongTurn++;
                }
            }
            for (Integer n : holding) {
                if (!drawn.contains(n)) {
                    missing++;
                }
            }
            for (Integer n : drawn) {
                if (!holding.contains(n)) {
                    extra++;
                }
            }
        }
        final int missingCount = missing;
        final int extraCount = extra;
        final int turnCount = wrongTurn;
        ok(missing == 0, "M1:every-holding-part-is-drawn " + name, () -> missingCount + " parts whose guard holds were not drawn");
        ok(extra == 0, "M1:no-part-when-guard-fails " + name, () -> extraCount + " parts whose guard fails were drawn");
        ok(wrongTurn == 0, "M2:turn-carried-exactly " + name, () -> turnCount + " parts drawn with a turn that is not theirs");
    }

    private void order(Mosaic display, MosaicCompilation.Compiled done) {
        MosaicPlan plan = done.plan();
        int unordered = 0;
        for (Map<String, Object> tuple : everyTuple(plan.mosaic())) {
            int last = -1;
            for (Placed p : plan.assemble(tuple)) {
                if (p.part() <= last) {
                    unordered++;
                }
                last = p.part();
            }
        }
        final int count = unordered;
        ok(unordered == 0, "M2:declaration-order", () -> count + " parts were drawn out of the order they are declared in");

        // hand-checked states: the picture, read off the mosaic and not computed
        Map<String, Object> lit = new LinkedHashMap<>();
        lit.put("north", "moon");
        lit.put("east", "sun");
        lit.put("south", "none");
        lit.put("west", "star");
        lit.put("lit", Boolean.TRUE);
        c.equal(List.of(new Placed("example:block/display_core", 0, 0), new Placed("example:block/display_side_moon", 0, 1),
                new Placed("example:block/display_side_sun", 90, 2), new Placed("example:block/display_side_none", 180, 3),
                new Placed("example:block/display_side_star", 270, 4), new Placed("example:block/display_flame", 0, 5),
                new Placed("example:block/display_glow_moon", 0, 6)), plan.assemble(lit), "M1:hand-checked-lit-state");
        Map<String, Object> dark = new LinkedHashMap<>(lit);
        dark.put("lit", Boolean.FALSE);
        dark.put("north", "none");
        dark.put("east", "none");
        dark.put("west", "none");
        c.equal(List.of(new Placed("example:block/display_core", 0, 0), new Placed("example:block/display_side_none", 0, 1),
                new Placed("example:block/display_side_none", 90, 2), new Placed("example:block/display_side_none", 180, 3),
                new Placed("example:block/display_side_none", 270, 4)), plan.assemble(dark), "M1:hand-checked-dark-state");
        Map<String, Object> sunGlow = new LinkedHashMap<>(lit);
        sunGlow.put("north", "sun");
        sunGlow.put("lit", Boolean.TRUE);
        ok(modelAt(plan.assemble(sunGlow), 6).equals("example:block/display_glow_sun"), "M1:guard-and-template-together",
                () -> String.valueOf(plan.assemble(sunGlow)));
        Map<String, Object> starGlow = new LinkedHashMap<>(lit);
        starGlow.put("north", "star");
        ok(plan.assemble(starGlow).size() == 6, "M1:guard-over-a-subset-of-values", () -> String.valueOf(plan.assemble(starGlow)));
    }

    private void independence(Mosaic display, MosaicCompilation.Compiled done) {
        MosaicPlan plan = done.plan();
        boolean dropped = true;
        for (Input i : plan.mosaic().inputs()) {
            dropped &= !i.name().equals("glow");
        }
        ok(dropped, "M1:unread-input-dropped", () -> "an input no part reads is still in the plan");
        MapView a = new MapView().with("example:display", "north", "sun").with("example:display", "lit", Boolean.TRUE).with("example:display", "glow", Long.valueOf(0));
        MapView b = new MapView().with("example:display", "north", "sun").with("example:display", "lit", Boolean.TRUE).with("example:display", "glow", Long.valueOf(3));
        c.equal(plan.assemble(a), plan.assemble(b), "M3:unread-input-changes-nothing");
        // an input only one part reads moves only that part
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("north", "moon");
        base.put("east", "none");
        base.put("south", "star");
        base.put("west", "none");
        base.put("lit", Boolean.TRUE);
        boolean only = true;
        List<Placed> before = plan.assemble(base);
        for (Object east : new Domain.Choice(List.of("none", "moon", "sun", "star")).values()) {
            Map<String, Object> moved = new LinkedHashMap<>(base);
            moved.put("east", east);
            List<Placed> after = plan.assemble(moved);
            for (int k = 0; k < before.size() && k < after.size(); k++) {
                if (before.get(k).part() != 2) {
                    only &= before.get(k).equals(after.get(k));
                }
            }
            only &= after.size() == before.size();
        }
        ok(only, "M3:input-only-moves-its-parts", () -> "changing the east side changed a part that does not read it");
        // absent and out-of-domain data read as the defaults, and only the part that read it is told so
        Assembly empty = plan.assemble(new MapView());
        ok(empty.inDomain() && empty.placed().size() == 5 && modelAt(empty.placed(), 1).equals("example:block/display_side_none"),
                "M1:absent-component-reads-default", () -> String.valueOf(empty));
        Assembly strange = plan.assemble(new MapView().with("example:display", "north", "bogus").with("example:display", "east", "sun"));
        ok(!strange.inDomain() && modelAt(strange.placed(), 1).equals("example:block/display_side_none")
                && modelAt(strange.placed(), 2).equals("example:block/display_side_sun"), "M1:out-of-domain-reads-default",
                () -> String.valueOf(strange));
        Assembly wrongKind = plan.assemble(new MapView().with("example:display", "lit", "yes"));
        ok(!wrongKind.inDomain() && wrongKind.placed().size() == 5, "M1:wrong-kind-reads-default", () -> String.valueOf(wrongKind));
    }

    private void manifest(Mosaic m, MosaicCompilation.Compiled done) {
        MosaicPlan plan = done.plan();
        String name = m.block();
        TreeSet<String> drawn = new TreeSet<>();
        for (Map<String, Object> tuple : everyTuple(plan.mosaic())) {
            for (Placed p : oracle(m, tuple)) {
                drawn.add(p.model());
            }
        }
        TreeSet<String> listed = new TreeSet<>(plan.manifest());
        List<String> missing = new ArrayList<>(drawn);
        missing.removeAll(listed);
        List<String> extra = new ArrayList<>(listed);
        extra.removeAll(drawn);
        ok(missing.isEmpty(), "M4:manifest-covers-every-drawn-model " + name, () -> "the manifest lacks " + missing);
        ok(extra.isEmpty(), "M4:manifest-has-no-undrawn-model " + name, () -> "the manifest lists models nothing draws: " + extra);
        List<String> sorted = new ArrayList<>(new TreeSet<>(plan.manifest()));
        c.equal(sorted, plan.manifest(), "M4:manifest-sorted-unique " + name);
        // an adapter that loads exactly the resource's manifest never meets a model it did not load
        MosaicPlan back = readBack(done);
        boolean covered = back != null;
        if (back != null) {
            TreeSet<String> loaded = new TreeSet<>(back.manifest());
            for (Map<String, Object> tuple : everyTuple(plan.mosaic())) {
                for (Placed p : back.assemble(tuple)) {
                    covered &= loaded.contains(p.model());
                }
            }
        }
        ok(covered, "M4:resource-manifest-covers-every-drawn-model " + name, () -> "an adapter loading the resource's manifest meets an unloaded model");
    }

    private MosaicPlan readBack(MosaicCompilation.Compiled done) {
        String text = done.artefacts().get(Layout.plan(done.plan().block()));
        if (text == null) {
            c.fail("M2:plan-resource-agrees the compiler wrote no plan resource");
            return null;
        }
        Result<MosaicPlan> r = ReferenceMosaicPlan.parse(text);
        if (r instanceof Result.Failed<MosaicPlan> failed) {
            c.fail("M2:plan-resource-agrees the plan resource does not read back: " + failed.diagnostics());
            return null;
        }
        return ((Result.Ok<MosaicPlan>) r).value();
    }

    private void degenerate() {
        Mosaic constant = Mosaics.mini("example:plinth", "", "{\"model\": \"example:block/plinth\"}");
        MosaicCompilation.Compiled one = compiled(constant);
        c.equal(List.of(new Placed("example:block/plinth", 0, 0)), one.plan().assemble(Collections.<String, Object>emptyMap()), "M1:constant-mosaic");
        c.equal(List.of("example:block/plinth"), one.plan().manifest(), "M4:constant-manifest");
        Mosaic empty = Mosaics.mini("example:void", "", "");
        MosaicCompilation.Compiled none = compiled(empty);
        c.equal(List.of(), none.plan().assemble(Collections.<String, Object>emptyMap()), "M1:empty-mosaic-draws-nothing");
        c.equal(List.of(), none.plan().manifest(), "M4:empty-manifest");
    }

    // --- M5 ------------------------------------------------------------------------------------------------------------

    private boolean refuses(Mosaic m, Diagnostic.Kind kind) {
        MosaicCompilation r = compiler.compile(m, catalog, MODERN);
        if (r instanceof MosaicCompilation.Rejected rejected) {
            for (Diagnostic d : rejected.diagnostics()) {
                if (d.kind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    private void projection() {
        String part = "{\"model\": \"example:block/x_{n}\"}";
        c.that(refuses(Mosaics.mini("example:vaulted", "\"n\": {\"component\": \"example:vault\", \"field\": \"mark\", \"domain\": {\"enum\": [\"a\", \"b\"]}}", part),
                Diagnostic.Kind.SERVER_PRIVATE), "M5:server-private-rejected");
        c.that(refuses(Mosaics.mini("example:ghosted", "\"n\": {\"component\": \"example:ghost\", \"field\": \"mark\", \"domain\": {\"enum\": [\"a\", \"b\"]}}", part),
                Diagnostic.Kind.CATALOG), "M5:unknown-component-rejected");
        c.that(refuses(Mosaics.mini("example:fielded", "\"n\": {\"component\": \"example:display\", \"field\": \"nowhere\", \"domain\": {\"enum\": [\"a\", \"b\"]}}", part),
                Diagnostic.Kind.CATALOG), "M5:unknown-field-rejected");
        c.that(refuses(Mosaics.mini("example:typed", "\"n\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"enum\": [\"a\", \"b\"]}}", part),
                Diagnostic.Kind.TYPE), "M5:type-mismatch-rejected");
        c.that(refuses(Mosaics.mini("example:unity", "\"n\": {\"component\": \"example:display\", \"domain\": \"present\"}", part),
                Diagnostic.Kind.TYPE), "M5:present-needs-a-unit-component");
        c.that(!refuses(Mosaics.mini("example:marked", "\"n\": {\"component\": \"minecraft:marker\", \"domain\": \"present\"}",
                "{\"when\": {\"n\": true}, \"model\": \"example:block/marked\"}"), Diagnostic.Kind.TYPE), "M5:present-on-a-unit-component-accepted");
    }

    // --- M7 ------------------------------------------------------------------------------------------------------------

    private void bounded() {
        int cap = compiler.maxSpan();
        String one = "\"a\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [0, " + (cap) + "]}}";
        Mosaic over = Mosaics.mini("example:over", one, "{\"model\": \"example:block/over_{a}\"}");
        boolean rejected;
        try {
            rejected = refuses(over, Diagnostic.Kind.TOO_LARGE);
        } catch (RuntimeException e) {
            rejected = false;
        }
        c.that(rejected, "M7:part-span-over-cap-rejected");

        String three = "\"a\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [0, 2000000000]}},"
                + "\"b\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [0, 2000000000]}},"
                + "\"d\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [0, 2000000000]}}";
        Mosaic huge = Mosaics.mini("example:huge", three, "{\"model\": \"example:block/huge_{a}_{b}_{d}\"}");
        boolean refused;
        long started = System.nanoTime();
        try {
            refused = refuses(huge, Diagnostic.Kind.TOO_LARGE);
        } catch (RuntimeException | OutOfMemoryError e) {
            refused = false;
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        final boolean quick = millis < 2000;
        ok(refused && quick, "M7:product-over-cap-rejected-before-enumeration", () -> "refused=" + (millis) + "ms");

        // the last value of the range brings the span exactly to the cap
        String atCap = "\"a\": {\"component\": \"example:display\", \"field\": \"glow\", \"domain\": {\"int\": [1, " + cap + "]}}";
        Mosaic exact = Mosaics.mini("example:exact", atCap, "{\"model\": \"example:block/exact_{a}\"}");
        c.that(!refuses(exact, Diagnostic.Kind.TOO_LARGE), "M7:span-at-cap-accepted");

        // parts are enumerated apart: four inputs of 28 values span 614,656 states together and 28 each apart
        Mosaic wide = Mosaics.parse(Mosaics.panel(28));
        long together = Evaluator.product(wide.inputs());
        ok(together > cap, "M7:the-wide-mosaic-spans-more-than-the-cap", () -> String.valueOf(together));
        MosaicCompilation r = compiler.compile(wide, catalog, MODERN);
        ok(r instanceof MosaicCompilation.Compiled done && done.plan().manifest().size() == 29, "M7:independent-parts-beyond-product-accepted",
                () -> r.toString());
    }

    // --- M6 ------------------------------------------------------------------------------------------------------------

    private void determinism() {
        Mosaic display = Mosaics.parse(Mosaics.DISPLAY);
        Map<String, String> first = compiled(display).artefacts();
        Map<String, String> again = compiled(Mosaics.parse(Mosaics.DISPLAY)).artefacts();
        c.equal(first, again, "M6:repeat-identical");
        String text = first.get(Layout.plan("example:display"));
        c.that(text != null && text.equals(Json.write(Json.parse(text))), "M6:canonical-json");
        boolean ascii = text != null && text.endsWith("\n") && text.indexOf('\r') < 0;
        for (int i = 0; text != null && i < text.length(); i++) {
            char ch = text.charAt(i);
            ascii &= ch == '\n' || (ch >= 0x20 && ch < 0x7f);
        }
        c.that(ascii, "M6:ascii-and-line-feeds");
        c.equal(first, compiled(Mosaics.parse(Mosaics.DISPLAY_REORDERED)).artefacts(), "M6:declaration-order-independent");
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            c.equal(first, compiled(Mosaics.parse(Mosaics.DISPLAY)).artefacts(), "M6:locale-independent");
        } finally {
            Locale.setDefault(saved);
        }
        // the order of the parts is the picture's own, and is kept
        Map<String, String> swapped = compiled(Mosaics.parse(Mosaics.DISPLAY_SWAPPED)).artefacts();
        c.that(!swapped.equals(first), "M6:part-order-is-kept");
        MosaicPlan plan = compiled(Mosaics.parse(Mosaics.DISPLAY_SWAPPED)).plan();
        Map<String, Object> state = new LinkedHashMap<>();
        for (String side : List.of("north", "east", "south", "west")) {
            state.put(side, "sun");
        }
        state.put("lit", Boolean.FALSE);
        ok(modelAt(plan.assemble(state), 0).equals("example:block/display_side_sun"), "M2:swapped-parts-draw-swapped",
                () -> String.valueOf(plan.assemble(state)));
    }

    // --- well-formedness, targets, the tool ----------------------------------------------------------------------------

    private boolean parseRefuses(String text, Diagnostic.Kind kind) {
        Result<Mosaic> r = Mosaic.parse(text);
        if (r instanceof Result.Failed<Mosaic> failed) {
            for (Diagnostic d : failed.diagnostics()) {
                if (d.kind() == kind) {
                    return true;
                }
            }
        }
        return false;
    }

    private void wellFormed() {
        String in = "\"inputs\": {\"n\": {\"component\": \"example:display\", \"field\": \"north\", \"domain\": {\"enum\": [\"a\", \"b\"]}}}";
        c.that(parseRefuses("{not json", Diagnostic.Kind.SYNTAX), "P1:syntax");
        c.that(parseRefuses("[]", Diagnostic.Kind.SCHEMA), "P1:not-an-object");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + "}", Diagnostic.Kind.SCHEMA), "P1:parts-required");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [], \"extra\": 1}", Diagnostic.Kind.SCHEMA), "P1:unknown-field");
        c.that(parseRefuses("{\"block\": \"Not A Block\", " + in + ", \"parts\": []}", Diagnostic.Kind.SCHEMA), "P1:block-id");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"model\": \"example:b\", \"turn\": 45}]}", Diagnostic.Kind.SCHEMA),
                "P1:turn-is-a-quarter-turn");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"model\": \"example:b\", \"turn\": \"left\"}]}", Diagnostic.Kind.SCHEMA),
                "P1:turn-is-a-number");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"model\": \"example:b\", \"spin\": 90}]}", Diagnostic.Kind.SCHEMA),
                "P1:part-unknown-field");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"when\": {\"m\": \"a\"}, \"model\": \"example:b\"}]}", Diagnostic.Kind.SCHEMA),
                "P1:guard-on-an-undeclared-input");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"when\": {\"n\": \"c\"}, \"model\": \"example:b\"}]}", Diagnostic.Kind.DOMAIN),
                "P1:guard-outside-the-domain");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"model\": \"example:b_{m}\"}]}", Diagnostic.Kind.TEMPLATE),
                "P1:template-names-an-undeclared-input");
        c.that(parseRefuses("{\"block\": \"example:x\", " + in + ", \"parts\": [{\"model\": \"Not A Model\"}]}", Diagnostic.Kind.TEMPLATE),
                "P1:model-reference");
        Result<Mosaic> sound = Mosaic.parse(Mosaics.DISPLAY);
        c.that(sound instanceof Result.Ok<Mosaic> ok && Mosaic.fromTree(ok.value().toTree()) instanceof Result.Ok<Mosaic> back
                && back.value().equals(ok.value()), "P1:tree-round-trip");
        // a compiler trusts nothing it was handed: a mosaic built in code, never parsed, is checked again
        Mosaic unsound = new Mosaic("example:x", List.of(), List.of(new Part(Collections.emptyMap(), "example:block/{ghost}", 0)));
        MosaicCompilation r = compiler.compile(unsound, catalog, MODERN);
        c.that(r instanceof MosaicCompilation.Rejected, "P1:compiler-rechecks-mosaic");
    }

    private void realization() {
        MosaicCompilation r = compiler.compile(Mosaics.parse(Mosaics.DISPLAY), catalog, new BlockTarget("1.12.2", BlockRealization.NONE));
        boolean named = r instanceof MosaicCompilation.Rejected rejected && rejected.diagnostics().get(0).kind() == Diagnostic.Kind.NO_REALIZATION
                && rejected.diagnostics().get(0).message().contains("1.12.2");
        c.that(named, "R1:no-realization-rejected");
    }

    private void tool() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("display.mosaic.json", Mosaics.DISPLAY);
        String report = MosaicTool.compile("1.21.1", "dynamic-model", files, Mosaics.CATALOG_JSON);
        Map<?, ?> tree = (Map<?, ?>) Json.parse(report);
        c.that(Boolean.TRUE.equals(tree.get("ok")) && ((Map<?, ?>) tree.get("files")).containsKey(Layout.plan("example:display")), "T1:compiles-over-text");
        c.equal(report, MosaicTool.compile("1.21.1", "dynamic-model", files, Mosaics.CATALOG_JSON), "T1:report-is-deterministic");
        c.that(!ok(MosaicTool.compile("1.21.1", "static-model", files, Mosaics.CATALOG_JSON)), "T1:unknown-realization-refused");
        c.that(!ok(MosaicTool.compile("1.12.2", "none", files, Mosaics.CATALOG_JSON)), "T1:no-realization-refused");
        Map<String, String> twice = new LinkedHashMap<>(files);
        twice.put("again.mosaic.json", Mosaics.DISPLAY);
        c.that(!ok(MosaicTool.compile("1.21.1", "dynamic-model", twice, Mosaics.CATALOG_JSON)), "T1:one-mosaic-per-block");
        Map<String, String> broken = new LinkedHashMap<>();
        broken.put("bad.mosaic.json", "{not json");
        c.that(!ok(MosaicTool.compile("1.21.1", "dynamic-model", broken, Mosaics.CATALOG_JSON)), "T1:malformed-mosaic-refused");
        c.that(!ok(MosaicTool.compile("1.21.1", "dynamic-model", files, "{not json")), "T1:malformed-catalog-refused");
        c.that(ok(MosaicTool.compile("1.21.1", "dynamic-model", Collections.<String, String>emptyMap(), Mosaics.CATALOG_JSON)), "T1:nothing-to-compile-is-fine");
    }

    private static boolean ok(String report) {
        return Boolean.TRUE.equals(((Map<?, ?>) Json.parse(report)).get("ok"));
    }

    // --- property-style: seeded random mosaics ----------------------------------------------------------------------------

    private void properties() {
        int bad = 0;
        String firstBad = "";
        for (long seed = 1; seed <= 40; seed++) {
            Seeded rnd = new Seeded(seed);
            String[] texts = random(rnd);
            ComponentCatalog cat = ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(texts[1])).value();
            Mosaic m = Mosaics.parse(texts[0]);
            MosaicCompilation r = compiler.compile(m, cat, MODERN);
            if (!(r instanceof MosaicCompilation.Compiled done)) {
                c.fail("P1:property-mosaic-compiles seed " + seed + " " + r);
                continue;
            }
            MosaicPlan plan = done.plan();
            TreeSet<String> drawn = new TreeSet<>();
            boolean agrees = true;
            for (Map<String, Object> tuple : Evaluator.tuples(plan.mosaic().readInputs())) {
                List<Placed> want = oracle(m, tuple);
                agrees &= want.equals(plan.assemble(tuple));
                for (Placed p : want) {
                    drawn.add(p.model());
                }
            }
            boolean exact = drawn.equals(new TreeSet<>(plan.manifest()));
            String text = done.artefacts().get(Layout.plan(m.block()));
            boolean canonical = text != null && text.equals(Json.write(Json.parse(text)));
            Result<MosaicPlan> back = text == null ? null : ReferenceMosaicPlan.parse(text);
            boolean read = back instanceof Result.Ok<MosaicPlan> okBack && okBack.value().manifest().equals(plan.manifest());
            if (!(agrees && exact && canonical && read) && bad++ == 0) {
                firstBad = "seed " + seed + " agrees=" + agrees + " exact=" + exact + " canonical=" + canonical + " read=" + read;
            }
        }
        final int count = bad;
        final String shown = firstBad;
        ok(bad == 0, "M1:properties-hold-on-random-mosaics", () -> count + " of 40 failed, first " + shown);
    }

    /** A random mosaic over one to three inputs and one to five parts, and the catalog that has its component. */
    private static String[] random(Seeded rnd) {
        int k = 1 + rnd.nextInt(3);
        StringBuilder inputs = new StringBuilder();
        StringBuilder fields = new StringBuilder();
        String[] kinds = new String[k];
        int[] sizes = new int[k];
        for (int i = 0; i < k; i++) {
            int kind = rnd.nextInt(3);
            kinds[i] = new String[] {"bool", "int", "enum"}[kind];
            String domain;
            if (kind == 0) {
                domain = "\"bool\"";
                sizes[i] = 2;
            } else if (kind == 1) {
                sizes[i] = 2 + rnd.nextInt(4);
                domain = "{\"int\": [0, " + (sizes[i] - 1) + "]}";
            } else {
                sizes[i] = 2 + rnd.nextInt(3);
                StringBuilder values = new StringBuilder();
                for (int v = 0; v < sizes[i]; v++) {
                    values.append(v > 0 ? ", " : "").append("\"v").append(v).append('"');
                }
                domain = "{\"enum\": [" + values + "]}";
            }
            inputs.append(i > 0 ? ", " : "").append("\"i").append(i).append("\": {\"component\": \"example:rnd\", \"field\": \"f").append(i)
                    .append("\", \"domain\": ").append(domain).append('}');
            fields.append(i > 0 ? ", " : "").append("\"f").append(i).append("\": \"").append(new String[] {"bool", "int", "string"}[kind]).append('"');
        }
        int n = 1 + rnd.nextInt(5);
        StringBuilder parts = new StringBuilder();
        for (int p = 0; p < n; p++) {
            StringBuilder when = new StringBuilder();
            int guards = rnd.nextInt(3);
            List<Integer> used = new ArrayList<>();
            for (int g = 0; g < guards; g++) {
                int i = rnd.nextInt(k);
                if (used.contains(i)) {
                    continue;
                }
                used.add(i);
                String value;
                if (kinds[i].equals("bool")) {
                    value = rnd.nextBoolean() ? "true" : "false";
                } else if (kinds[i].equals("int")) {
                    value = rnd.nextBoolean() ? String.valueOf(rnd.nextInt(sizes[i])) : "{\"min\": " + rnd.nextInt(sizes[i]) + "}";
                } else {
                    value = rnd.nextBoolean() ? "\"v" + rnd.nextInt(sizes[i]) + "\"" : "[\"v0\", \"v" + (sizes[i] - 1) + "\"]";
                }
                when.append(when.length() > 0 ? ", " : "").append("\"i").append(i).append("\": ").append(value);
            }
            String model = "example:block/p" + p + (rnd.nextBoolean() ? "_{i" + rnd.nextInt(k) + "}" : "");
            parts.append(p > 0 ? ", " : "").append("{\"when\": {").append(when).append("}, \"model\": \"").append(model)
                    .append("\", \"turn\": ").append(90 * rnd.nextInt(4)).append('}');
        }
        String mosaic = "{\"block\": \"example:rnd\", \"inputs\": {" + inputs + "}, \"parts\": [" + parts + "]}";
        String catalog = "{\"schema\": 1, \"components\": {\"example:rnd\": {\"networkSynced\": true, \"fields\": {" + fields + "}}}}";
        return new String[] {mosaic, catalog};
    }
}
