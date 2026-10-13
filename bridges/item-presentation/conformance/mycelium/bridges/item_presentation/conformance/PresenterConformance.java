package mycelium.bridges.item_presentation.conformance;

import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentId;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.OnQuarantine;
import mycelium.item_component.v1.model.RecordCodec;
import mycelium.item_component.v1.model.StackCarrier;
import mycelium.item_component.v1.model.Unit;
import mycelium.item_presentation.v1.model.Compilation;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Evaluator;
import mycelium.item_presentation.v1.model.Input;
import mycelium.item_presentation.v1.model.Lock;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.Program;
import mycelium.item_presentation.v1.model.ReferenceSigil;
import mycelium.item_presentation.v1.model.Result;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.StackPresenter;
import mycelium.item_presentation.v1.model.Target;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Conformance for {@link StackPresenter}{@code <ItemStack>} on a real game (decision 0016, the port of
 * {@code item.presentation@1}): the cases a mod imports into its game tests, written against the port and not against any
 * variant, so they apply to the bridge's presenter and to a mod's own override alike.
 *
 * <p>The cases compile a small program of their own (a record, a mark and a string component: every kind of input) with a lock
 * whose codes are deliberately not the order of enumeration, for the realization this Minecraft version draws by, and hold the
 * presenter to it on real stacks. Every selection of the program is written onto a stack and read back, by the presenter and by
 * the game's own saved form of the stack ({@link Wire#rawSelector}), which is the independent witness: the legacy code on the
 * stack is the lock's, the modern key is the model reference.
 *
 * <pre>
 * for (PresenterConformance.Case c : PresenterConformance.cases(ItemCarrier::of, ItemPresenter::of)) {
 *     register("item_presentation_" + c.name(), helper -&gt; c.body().run(helper.getLevel()));
 * }
 * </pre>
 *
 * <p>{@link Wire} is the one part that differs by variant: how to save, send and compare a real stack and read its custom model
 * data from outside the presenter.
 */
public final class PresenterConformance {
    private PresenterConformance() {
    }

    /** One case: what it is called and what it checks. */
    public record Case(String name, Body body) {
    }

    @FunctionalInterface
    public interface Body {
        /** Throws {@link AssertionError} when a law fails. {@code level} is the level the game test runs in. */
        void run(Level level) throws Exception;
    }

    /** Builds the carrier a presenter reads stacks through (the {@code item-carrier} bridge's is {@code ItemCarrier::of}). */
    @FunctionalInterface
    public interface Carriers {
        StackCarrier<ItemStack> over(ComponentRegistry registry);
    }

    /** Builds the presenter under test (the bridge's is {@code ItemPresenter::of}; an override gives its own constructor). */
    @FunctionalInterface
    public interface Presenters {
        StackPresenter<ItemStack> over(Plan plan, ComponentRegistry registry, ComponentCatalog catalog, StackCarrier<ItemStack> carrier);
    }

    // ---------------------------------------------------------------------------------------------------- the program

    /** A record: how mended a ring is, and how cracked. */
    public record Look(int seams, int cracks) {
    }

    private static final RecordCodec.Field<Look, Integer> SEAMS = RecordCodec.field("seams", ComponentCodec.INT, Look::seams);
    private static final RecordCodec.Field<Look, Integer> CRACKS = RecordCodec.field("cracks", ComponentCodec.INT, Look::cracks);

    static final ComponentDefinition<Look> LOOK = RecordCodec.of(r -> new Look(r.get(SEAMS), r.get(CRACKS)), SEAMS, CRACKS)
            .definition(ComponentId.of("conformance:look"), null);
    static final ComponentDefinition<Unit> LIT = ComponentDefinition.presence(ComponentId.of("conformance:lit"));
    static final ComponentDefinition<String> PART = ComponentDefinition.withoutDefault(ComponentId.of("conformance:part"), ComponentCodec.STRING);

    static final ComponentRegistry REGISTRY = ComponentRegistry.builder().register(LOOK).register(LIT).register(PART).build();

    private static final String CATALOG = "{\"schema\": 1, \"components\": {"
            + "\"conformance:look\": {\"networkSynced\": true, \"fields\": {\"seams\": \"int\", \"cracks\": \"int\"}},"
            + "\"conformance:lit\": {\"networkSynced\": true, \"unit\": true},"
            + "\"conformance:part\": {\"networkSynced\": true, \"value\": \"string\"}}}";

    static final String ITEM = "conformance:probe";

    private static final String PROGRAM = "{\"item\": \"" + ITEM + "\", \"base\": \"conformance:item/probe_base\", \"inputs\": {"
            + "\"seams\": {\"component\": \"conformance:look\", \"field\": \"seams\", \"domain\": {\"int\": [0, 2]}},"
            + "\"cracks\": {\"component\": \"conformance:look\", \"field\": \"cracks\", \"domain\": {\"int\": [0, 1]}},"
            + "\"lit\": {\"component\": \"conformance:lit\", \"domain\": \"present\"},"
            + "\"part\": {\"component\": \"conformance:part\", \"domain\": {\"enum\": [\"none\", \"knot\", \"thread\"]}}},"
            + "\"cases\": ["
            + "{\"when\": {\"part\": \"knot\"}, \"model\": \"conformance:item/probe_knot\"},"
            + "{\"when\": {\"part\": \"thread\"}, \"model\": \"conformance:item/probe_thread\"},"
            + "{\"when\": {\"lit\": true}, \"model\": \"conformance:item/probe_lit\"},"
            + "{\"when\": {\"seams\": {\"min\": 1}, \"cracks\": {\"min\": 1}}, \"model\": \"conformance:item/probe_mended_{seams}_cracked\"},"
            + "{\"when\": {\"seams\": {\"min\": 1}}, \"model\": \"conformance:item/probe_mended_{seams}\"},"
            + "{\"when\": {\"cracks\": {\"min\": 1}}, \"model\": \"conformance:item/probe_cracked\"}]}";

    /** The lock the cases compile with: codes in no order a compiler would pick, with a gap, so a code is the lock's or it is wrong. */
    private static Lock lock() {
        List<Lock.Entry> entries = new ArrayList<>();
        int[] codes = {3, 4, 9, 17, 18, 40, 41, 100};
        String[] keys = {"probe_cracked", "probe_knot", "probe_lit", "probe_mended_1", "probe_mended_1_cracked", "probe_mended_2",
                "probe_mended_2_cracked", "probe_thread"};
        for (int i = 0; i < keys.length; i++) {
            entries.add(new Lock.Entry(codes[i], "conformance:item/" + keys[i]));
        }
        return Lock.empty().plus(ITEM, entries);
    }

    /** The compiled program, for the realization this Minecraft version draws by. */
    private static Plan plan() {
        Program program = ((Result.Ok<Program>) Program.parse(PROGRAM)).value();
        ComponentCatalog catalog = catalog();
        Compilation done = new ReferenceSigil().compile(program, lock(), catalog, new Target(Wire.VERSION, Wire.REALIZATION));
        if (done instanceof Compilation.Rejected rejected) {
            throw new AssertionError("the conformance program does not compile: " + rejected.diagnostics());
        }
        return ((Compilation.Compiled) done).plan();
    }

    private static ComponentCatalog catalog() {
        return ((Result.Ok<ComponentCatalog>) ComponentCatalog.parse(CATALOG)).value();
    }

    private static Program program() {
        return plan().program();
    }

    // ---------------------------------------------------------------------------------------------------- the cases

    /**
     * The cases, for a presenter built by {@code presenters} over a carrier built by {@code carriers} (the bridge's are
     * {@code ItemCarrier::of} and {@code ItemPresenter::of}).
     */
    public static List<Case> cases(Carriers carriers, Presenters presenters) {
        Function<Plan, StackPresenter<ItemStack>> make = plan -> {
            return presenters.over(plan, REGISTRY, catalog(), carriers.over(REGISTRY));
        };
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("every_selection_round_trips", level -> roundTrips(level, make.apply(plan()))));
        cases.add(new Case("codes_are_the_locks", level -> codesAreTheLocks(level, make.apply(plan()))));
        cases.add(new Case("present_reads_the_stacks_own_components", level -> presentReads(level, make.apply(plan()), carriers)));
        cases.add(new Case("base_clears_the_selector", level -> baseClears(level, make.apply(plan()))));
        cases.add(new Case("neighbours_kept", level -> neighboursKept(level, make.apply(plan()))));
        cases.add(new Case("equal_state_stacks", level -> stacking(level, make.apply(plan()))));
        cases.add(new Case("copy_save_and_sync", level -> copySaveSync(make.apply(plan()), level)));
        cases.add(new Case("raw_item_data", level -> rawItemData(make.apply(plan()), level)));
        cases.add(new Case("empty_stack_untouched", level -> emptyStack(make.apply(plan()))));
        return cases;
    }

    /** Every tuple of the program's inputs, the state a stack would carry for it. */
    private static List<Map<String, Object>> tuples() {
        return Evaluator.tuples(program().readInputs());
    }

    private static CarrierSnapshot snapshot(Map<String, Object> tuple) {
        CarrierSnapshot state = CarrierSnapshot.empty()
                .with(LOOK, new Look(((Long) tuple.get("seams")).intValue(), ((Long) tuple.get("cracks")).intValue()));
        if (Boolean.TRUE.equals(tuple.get("lit"))) {
            state = state.with(LIT, Unit.INSTANCE);
        }
        String part = (String) tuple.get("part");
        if (!part.equals("none")) {
            state = state.with(PART, part);
        }
        return state;
    }

    private static Selector expected(Plan plan, Map<String, Object> tuple) {
        return plan.selectorOf(plan.select(tuple));
    }

    /** S1 of the port: what is written is what is read back, by the presenter and by the stack's own saved form. */
    private static void roundTrips(Level level, StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        int selections = 0;
        for (Map<String, Object> tuple : tuples()) {
            CarrierSnapshot state = snapshot(tuple);
            Selection selection = presenter.select(state);
            Selector want = expected(plan, tuple);
            expect("the snapshot " + tuple + " selects " + want, plan.selectorOf(selection).equals(want));
            ItemStack stack = Wire.plain();
            Selector wrote = presenter.apply(stack, selection);
            expect(tuple + ": apply returns what the plan said", wrote.equals(want));
            expect(tuple + ": the presenter reads back " + want + ", not " + presenter.read(stack), presenter.read(stack).equals(want));
            expect(tuple + ": the game's own form of the stack holds " + want + ", not " + Wire.rawSelector(level, stack),
                    Wire.rawSelector(level, stack).equals(want));
            if (!selection.isBase()) {
                selections++;
            }
        }
        expect("the program draws other models than its base (" + selections + " tuples)", selections > 0);
    }

    /** The legacy code on the stack is the lock's, and the modern key is the model reference. */
    private static void codesAreTheLocks(Level level, StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        Lock lock = lock();
        int checked = 0;
        for (Plan.Entry entry : plan.entries()) {
            expect(entry.key() + " has the lock's code " + lock.codeOf(ITEM, entry.key()) + ", not " + entry.code(),
                    Integer.valueOf(entry.code()).equals(lock.codeOf(ITEM, entry.key())));
            ItemStack stack = Wire.plain();
            Selection selection = plan.select(tupleDrawing(entry.key()));
            presenter.apply(stack, selection);
            Selector onStack = Wire.rawSelector(level, stack);
            if (Wire.REALIZATION == mycelium.item_presentation.v1.model.Realization.ITEM_DEFINITION) {
                expect(entry.key() + " is on the stack as its model reference, not " + onStack, onStack.equals(new Selector.Key(entry.key())));
            } else {
                expect(entry.key() + " is on the stack as code " + entry.code() + ", not " + onStack, onStack.equals(new Selector.Code(entry.code())));
            }
            checked++;
        }
        expect("the program has selections to check (" + checked + ")", checked >= 6);
    }

    private static Map<String, Object> tupleDrawing(String model) {
        for (Map<String, Object> tuple : tuples()) {
            if (Evaluator.evaluate(program(), tuple).equals(model)) {
                return tuple;
            }
        }
        throw new AssertionError("no state draws " + model);
    }

    /** {@code present} selects for the components the stack carries through its carrier, and writes it. */
    private static void presentReads(Level level, StackPresenter<ItemStack> presenter, Carriers carriers) {
        StackCarrier<ItemStack> carrier = carriers.over(REGISTRY);
        Plan plan = presenter.plan();
        for (Map<String, Object> tuple : tuples()) {
            ItemStack stack = Wire.plain();
            CarrierSnapshot state = snapshot(tuple);
            carrier.write(stack, state, OnQuarantine.KEEP);
            Selection selected = presenter.select(stack);
            expect(tuple + ": select(stack) agrees with select(snapshot)", selected.model().equals(presenter.select(state).model()));
            Selector wrote = presenter.present(stack);
            Selector want = expected(plan, tuple);
            expect(tuple + ": present wrote " + wrote + ", the plan says " + want, wrote.equals(want));
            expect(tuple + ": the stack carries " + want + ", not " + Wire.rawSelector(level, stack), Wire.rawSelector(level, stack).equals(want));
            expect(tuple + ": the stack's state is untouched by drawing it", carrier.read(stack).components().size() == state.components().size());
        }
        ItemStack bare = Wire.plain();
        expect("a stack carrying nothing selects the base model", presenter.select(bare).isBase() && presenter.present(bare).equals(new Selector.None()));
    }

    /** The base model leaves no selector, and a stack that carried nothing else is a plain one again. */
    private static void baseClears(Level level, StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        Map<String, Object> drawn = tupleDrawing("conformance:item/probe_lit");
        ItemStack stack = Wire.plain();
        presenter.apply(stack, plan.select(drawn));
        expect("a selector was written", !presenter.read(stack).equals(new Selector.None()));
        Selector cleared = presenter.apply(stack, presenter.select(CarrierSnapshot.empty()));
        expect("the base model writes nothing", cleared.equals(new Selector.None()));
        expect("the presenter reads nothing back", presenter.read(stack).equals(new Selector.None()));
        expect("the game's own form holds none", Wire.rawSelector(level, stack).equals(new Selector.None()));
        expect("a stack that carried nothing else is again a plain one", Wire.same(stack, Wire.plain()) && Wire.carriesNoModelData(stack));
    }

    /** A selector is the only custom model data the presenter touches. */
    private static void neighboursKept(Level level, StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        ItemStack stack = Wire.plain();
        Wire.addNeighbour(stack);
        presenter.apply(stack, plan.select(tupleDrawing("conformance:item/probe_lit")));
        expect("another mod's data survives a selector written over it", Wire.hasNeighbour(stack));
        presenter.apply(stack, plan.select(tupleDrawing("conformance:item/probe_knot")));
        expect("... and a selector replaced", Wire.hasNeighbour(stack) && !presenter.read(stack).equals(new Selector.None()));
        presenter.apply(stack, presenter.select(CarrierSnapshot.empty()));
        expect("... and a selector cleared", Wire.hasNeighbour(stack) && presenter.read(stack).equals(new Selector.None()));
        expect("the neighbour alone is not taken for a selector", Wire.rawSelector(level, stack).equals(new Selector.None()));
    }

    /** Equal selections make equal stacks, which stack together; different selections do not. */
    private static void stacking(Level level, StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        ItemStack a = Wire.plain();
        ItemStack b = Wire.plain();
        presenter.apply(a, plan.select(tupleDrawing("conformance:item/probe_lit")));
        presenter.apply(b, plan.select(tupleDrawing("conformance:item/probe_lit")));
        expect("the same selection is the same stack", Wire.same(a, b) && Wire.equal(a, b));
        presenter.apply(b, plan.select(tupleDrawing("conformance:item/probe_knot")));
        expect("another selection is another stack", !Wire.same(a, b));
        presenter.apply(a, plan.select(tupleDrawing("conformance:item/probe_knot")));
        expect("... until they agree again", Wire.same(a, b));
    }

    /** A selector survives a copy, a save and reload, and the packet that sends the stack to a client. */
    private static void copySaveSync(StackPresenter<ItemStack> presenter, Level level) {
        Plan plan = presenter.plan();
        for (Map<String, Object> tuple : tuples()) {
            Selector want = expected(plan, tuple);
            if (want instanceof Selector.None) {
                continue;
            }
            ItemStack stack = Wire.plain();
            presenter.apply(stack, plan.select(tuple));
            expect(tuple + ": a copy reads " + want, presenter.read(Wire.copy(stack)).equals(want));
            expect(tuple + ": a saved and reloaded stack reads " + want, presenter.read(Wire.saveAndReload(level, stack)).equals(want));
            expect(tuple + ": a stack sent to a client reads " + want, presenter.read(Wire.sync(level, stack)).equals(want));
        }
    }

    /** The raw item data a display entity is built from reads back as the same selector, and keeps what else it carried. */
    private static void rawItemData(StackPresenter<ItemStack> presenter, Level level) {
        mycelium.item_presentation.v1.model.Realization realization = Wire.REALIZATION;
        Plan plan = presenter.plan();
        if (!Wire.writesRaw(presenter)) {
            return; // an override writes raw item data its own way, and has its own cases for it
        }
        for (Map<String, Object> tuple : tuples()) {
            Selector want = expected(plan, tuple);
            CompoundTag item = Wire.itemData(level, Wire.plain());
            Wire.writeRaw(presenter, item, snapshot(tuple));
            ItemStack loaded = Wire.fromItemData(level, item);
            expect(tuple + ": raw item data loads as a stack reading " + want + ", not " + presenter.read(loaded) + " (" + realization.id() + ")",
                    presenter.read(loaded).equals(want));
            expect(tuple + ": the loaded stack's own saved form holds " + want, Wire.rawSelector(level, loaded).equals(want));
        }
        // what else the item data carried stays: another mod's data, and a selector written over a selector
        ItemStack neighbour = Wire.plain();
        Wire.addNeighbour(neighbour);
        CompoundTag item = Wire.itemData(level, neighbour);
        Wire.writeRaw(presenter, item, snapshot(tupleDrawing("conformance:item/probe_lit")));
        Wire.writeRaw(presenter, item, snapshot(tupleDrawing("conformance:item/probe_knot")));
        ItemStack loaded = Wire.fromItemData(level, item);
        expect("raw item data keeps the other mod's data and holds the last selector",
                Wire.hasNeighbour(loaded) && presenter.read(loaded).equals(expected(plan, tupleDrawing("conformance:item/probe_knot"))));
        CompoundTag fresh = Wire.itemData(level, neighbour);
        Wire.writeRaw(presenter, fresh, snapshot(Map.<String, Object>of("seams", 0L, "cracks", 0L, "lit", false, "part", "none")));
        ItemStack bare = Wire.fromItemData(level, fresh);
        expect("the base model writes no selector into raw item data, and the neighbour stays",
                presenter.read(bare).equals(new Selector.None()) && Wire.hasNeighbour(bare));
    }

    /** An empty stack carries nothing and cannot: it is neither changed nor an error. */
    private static void emptyStack(StackPresenter<ItemStack> presenter) {
        Plan plan = presenter.plan();
        ItemStack empty = ItemStack.EMPTY;
        presenter.apply(empty, plan.select(tupleDrawing("conformance:item/probe_lit")));
        expect("an empty stack reads nothing", presenter.read(empty).equals(new Selector.None()) && empty.isEmpty());
        expect("an empty stack selects the base model", presenter.select(empty).isBase());
    }

    private static void expect(String what, boolean holds) {
        if (!holds) {
            throw new AssertionError(what);
        }
    }

    /** The inputs of the cases' program, for a mod that wants to see what they cover. */
    public static List<String> inputs() {
        List<String> out = new ArrayList<>();
        for (Input i : program().readInputs()) {
            out.add(i.name());
        }
        return out;
    }
}
