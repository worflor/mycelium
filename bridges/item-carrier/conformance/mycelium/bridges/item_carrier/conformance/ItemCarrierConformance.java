package mycelium.bridges.item_carrier.conformance;

import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentId;
import mycelium.item_component.v1.model.ComponentRead;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.LegacyKeyMigration;
import mycelium.item_component.v1.model.OnQuarantine;
import mycelium.item_component.v1.model.RecordCodec;
import mycelium.item_component.v1.model.StackCarrier;
import mycelium.item_component.v1.model.Unit;
import mycelium.item_component.v1.model.WriteOutcome;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Conformance for {@link StackCarrier}{@code <ItemStack>} on a real game (decision 0016, laws S1 to S6 of
 * {@code item.component@1}): the cases a mod imports into its game tests, written against the port and not against any
 * variant, so they apply to the bridge's carrier and to a mod's own override alike.
 *
 * <p>A case is a name and a body that throws {@link AssertionError} when a law fails. Registering them is the mod's: each
 * loader registers game tests its own way, so a mod runs the cases from whatever test it has, handing each the level it
 * runs in (the codecs of a modern stack need its registries):
 *
 * <pre>
 * for (ItemCarrierConformance.Case c : ItemCarrierConformance.cases(ItemCarrier::of)) {
 *     register("item_carrier_" + c.name(), helper -&gt; c.body().run(helper.getLevel()));
 * }
 * </pre>
 *
 * <p>The cases build their own component registry, so they never touch the mod's state. {@link Wire} is the one part that
 * differs by variant: how to copy, save and reload, send and compare a real stack, and reach its raw tag.
 */
public final class ItemCarrierConformance {
    private ItemCarrierConformance() {
    }

    /** One case: what it is called and what it checks. */
    public record Case(String name, Body body) {
    }

    @FunctionalInterface
    public interface Body {
        /** Throws {@link AssertionError} when a law fails. {@code level} is the level the game test runs in. */
        void run(Level level) throws Exception;
    }

    /** A record, to put a compound (not only a scalar) in the stack. */
    public record Pair(String left, int right) {
    }

    private static final RecordCodec.Field<Pair, String> LEFT = RecordCodec.field("left", ComponentCodec.STRING, Pair::left);
    private static final RecordCodec.Field<Pair, Integer> RIGHT = RecordCodec.field("right", ComponentCodec.INT, Pair::right);

    static final ComponentDefinition<String> NAME = ComponentDefinition.withoutDefault(ComponentId.of("conformance:name"), ComponentCodec.STRING);
    /** Zero is a real value of this one, not "unset": it must be written as zero. */
    static final ComponentDefinition<Integer> COUNT = ComponentDefinition.withoutDefault(ComponentId.of("conformance:count"), ComponentCodec.INT);
    static final ComponentDefinition<Unit> MARK = ComponentDefinition.presence(ComponentId.of("conformance:mark"));
    static final ComponentDefinition<Pair> PAIR = RecordCodec.of(r -> new Pair(r.get(LEFT), r.get(RIGHT)), LEFT, RIGHT)
            .definition(ComponentId.of("conformance:pair"), null);

    static final ComponentRegistry REGISTRY = ComponentRegistry.builder()
            .register(NAME).register(COUNT).register(MARK).register(PAIR).build();

    /** Where {@link #COUNT} used to live, to prove the old-key migration on a real stack. */
    static final String OLD_COUNT = "conformance_old_count";
    static final LegacyKeyMigration MIGRATION = LegacyKeyMigration.builder().key(OLD_COUNT, COUNT, ComponentCodec.INT).build();

    private static final String OTHER = "othermod:data";

    /**
     * The cases, for a carrier built by {@code factory} from a registry and a migration (the bridge's is
     * {@code ItemCarrier::of}; an override gives its own constructor).
     */
    public static List<Case> cases(BiFunction<ComponentRegistry, LegacyKeyMigration, StackCarrier<ItemStack>> factory) {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("round_trip", level -> roundTrip(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        cases.add(new Case("cleared_is_plain", level -> clearedIsPlain(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        cases.add(new Case("other_keys_kept", level -> otherKeysKept(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        cases.add(new Case("quarantine_refused_not_lost", level -> quarantine(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        cases.add(new Case("copy", level -> copies(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        cases.add(new Case("save_and_reload", level -> saveAndReload(factory.apply(REGISTRY, LegacyKeyMigration.none()), level)));
        cases.add(new Case("sync_to_client", level -> sync(factory.apply(REGISTRY, LegacyKeyMigration.none()), level)));
        cases.add(new Case("old_keys", level -> oldKeys(factory.apply(REGISTRY, MIGRATION))));
        cases.add(new Case("empty_and_unchanged", level -> emptyAndUnchanged(factory.apply(REGISTRY, LegacyKeyMigration.none()))));
        return cases;
    }

    // ---------------------------------------------------------------------------------------------------- S1

    /** S1: what is set is read back, for a scalar, a zero that means zero, a mark and a record; and another value replaces it. */
    private static void roundTrip(StackCarrier<ItemStack> items) {
        ItemStack stack = Wire.plain();
        written(items.set(stack, NAME, "Wolf"), "S1 set a string");
        written(items.set(stack, COUNT, 0), "S1 set a zero");
        written(items.set(stack, MARK, Unit.INSTANCE), "S1 set a mark");
        written(items.set(stack, PAIR, new Pair("a", 7)), "S1 set a record");
        expect("S1 the string", "Wolf".equals(items.get(stack, NAME).orElse(null)));
        expect("S1 zero is a value, not unset", Integer.valueOf(0).equals(items.get(stack, COUNT).orElse(null)));
        expect("S1 the mark", items.has(stack, MARK));
        expect("S1 the record", new Pair("a", 7).equals(items.get(stack, PAIR).orElse(null)));
        expect("S1 the snapshot holds all four", items.read(stack).components().size() == 4);
        written(items.set(stack, NAME, "Steve"), "S1 replace");
        expect("S1 the replaced value", "Steve".equals(items.get(stack, NAME).orElse(null)));
        expect("S1 a component not set is absent", !items.has(Wire.plain(), NAME) && items.read(Wire.plain(), NAME) instanceof ComponentRead.Absent);
        // the same state, set in another order, is an equal stack; another value is not
        ItemStack a = Wire.plain();
        ItemStack b = Wire.plain();
        items.set(a, NAME, "x");
        items.set(a, COUNT, 3);
        items.set(b, COUNT, 3);
        items.set(b, NAME, "x");
        expect("S1 the same state in another order is the same stack", Wire.same(a, b) && Wire.equal(a, b));
        items.set(b, COUNT, 4);
        expect("S1 another value is another stack", !Wire.same(a, b));
    }

    // ---------------------------------------------------------------------------------------------------- S2

    /** S2: a stack with nothing left to carry is a plain stack: it holds nothing of its own, equals one and stacks with it. */
    private static void clearedIsPlain(StackCarrier<ItemStack> items) {
        ItemStack stack = Wire.plain();
        items.set(stack, NAME, "Wolf");
        items.set(stack, MARK, Unit.INSTANCE);
        expect("S2 a stack that carries something is not plain", !Wire.same(stack, Wire.plain()));
        written(items.clear(stack, NAME), "S2 clear one");
        expect("S2 a stack that carries one mark still holds it", items.has(stack, MARK) && !Wire.carriesNothing(stack));
        written(items.clear(stack, MARK), "S2 clear the last");
        expect("S2 nothing left: it holds no tag of its own (a null tag, not an empty one)", Wire.carriesNothing(stack));
        expect("S2 nothing left: it stacks with a plain stack", Wire.same(stack, Wire.plain()));
        expect("S2 nothing left: it equals a plain stack", Wire.equal(stack, Wire.plain()));
        WriteOutcome again = items.clear(stack, MARK);
        expect("S2 clearing what is not there changes nothing", again instanceof WriteOutcome.Written w && !w.changed());
    }

    // ---------------------------------------------------------------------------------------------------- S3

    /** S3: another mod's keys at the root survive every write, and are not made plain by clearing ours. */
    private static void otherKeysKept(StackCarrier<ItemStack> items) {
        ItemStack stack = Wire.plain();
        Wire.putText(stack, OTHER, "theirs");
        items.set(stack, NAME, "mine");
        expect("S3 the other mod's key survives a set", "theirs".equals(Wire.text(stack, OTHER)));
        items.set(stack, COUNT, 2);
        items.clear(stack, NAME);
        expect("S3 the other mod's key survives a clear", Wire.has(stack, OTHER));
        items.clear(stack, COUNT);
        expect("S3 with ours all cleared the other mod's key is all that is left", Wire.has(stack, OTHER)
                && !Wire.carriesNothing(stack) && items.read(stack).components().isEmpty());
        items.write(stack, CarrierSnapshot.empty().with(NAME, "again"));
        expect("S3 a whole-snapshot write keeps it too", Wire.has(stack, OTHER) && "again".equals(items.get(stack, NAME).orElse(null)));
    }

    // ---------------------------------------------------------------------------------------------------- S4

    /** S4: a write that meets unreadable data is refused and leaves the stack as it was; KEEP applies it and keeps the data. */
    private static void quarantine(StackCarrier<ItemStack> items) {
        // a component entry of the wrong type
        ItemStack stack = Wire.plain();
        items.set(stack, NAME, "fine");
        Wire.putFencedText(stack, COUNT.id().toString(), "not-an-int");
        ItemStack before = Wire.copy(stack);
        String dumpBefore = Wire.rootText(stack);
        expect("S4 the unreadable entry reads as quarantined, not absent", items.read(stack, COUNT).isQuarantined());
        expect("S4 the others still read", "fine".equals(items.get(stack, NAME).orElse(null)));
        WriteOutcome refused = items.set(stack, COUNT, 5);
        expect("S4 setting over an unreadable entry is refused", refused instanceof WriteOutcome.Refused r
                && r.reason() == WriteOutcome.Reason.COMPONENT_QUARANTINED);
        expect("S4 a refusal leaves the stack exactly as it was", Wire.rootText(stack).equals(dumpBefore) && Wire.equal(before, stack));
        written(items.set(stack, COUNT, 5, OnQuarantine.KEEP), "S4 KEEP applies the write");
        expect("S4 the new value reads", Integer.valueOf(5).equals(items.get(stack, COUNT).orElse(null)));
        expect("S4 KEEP keeps the unreadable data beside it", Wire.rootText(stack).contains("not-an-int"));

        // a fence that is not a compound
        ItemStack odd = Wire.plain();
        Wire.putInt(odd, "mycelium$c", 9);
        ItemStack oddBefore = Wire.copy(odd);
        expect("S4 an unreadable fence reads as quarantined", items.read(odd, NAME).isQuarantined());
        WriteOutcome refusedEnvelope = items.set(odd, NAME, "x");
        expect("S4 writing over an unreadable fence is refused", refusedEnvelope instanceof WriteOutcome.Refused r
                && r.reason() == WriteOutcome.Reason.ENVELOPE_QUARANTINED);
        expect("S4 the stack with the unreadable fence is untouched", Wire.equal(oddBefore, odd) && Wire.rootText(odd).contains("9"));
    }

    // ---------------------------------------------------------------------------------------------------- S5

    /** S5 (copy): a copy reads the same state, and is its own stack. */
    private static void copies(StackCarrier<ItemStack> items) {
        ItemStack stack = Wire.plain();
        items.set(stack, PAIR, new Pair("p", 1));
        items.set(stack, MARK, Unit.INSTANCE);
        ItemStack copy = Wire.copy(stack);
        expect("S5 a copy is the same stack", Wire.same(stack, copy) && Wire.equal(stack, copy));
        expect("S5 a copy reads the same", new Pair("p", 1).equals(items.get(copy, PAIR).orElse(null)) && items.has(copy, MARK));
        items.set(copy, PAIR, new Pair("q", 2));
        items.clear(copy, MARK);
        expect("S5 changing the copy leaves the original", new Pair("p", 1).equals(items.get(stack, PAIR).orElse(null)) && items.has(stack, MARK));
    }

    /** S5 (save): saved through the game's own stack codec and loaded again, a stack reads the same and is the same stack. */
    private static void saveAndReload(StackCarrier<ItemStack> items, Level level) throws Exception {
        ItemStack stack = Wire.plain();
        items.set(stack, NAME, "Wolf");
        items.set(stack, COUNT, 0);
        items.set(stack, MARK, Unit.INSTANCE);
        items.set(stack, PAIR, new Pair("p", 1));
        Wire.putText(stack, OTHER, "theirs");
        ItemStack loaded = Wire.saveAndReload(level, stack);
        expect("S5 a reloaded stack is the same stack", Wire.same(stack, loaded) && Wire.equal(stack, loaded));
        expect("S5 a reloaded stack reads the same", "Wolf".equals(items.get(loaded, NAME).orElse(null))
                && Integer.valueOf(0).equals(items.get(loaded, COUNT).orElse(null)) && items.has(loaded, MARK)
                && new Pair("p", 1).equals(items.get(loaded, PAIR).orElse(null)));
        expect("S5 and keeps the other mod's key", Wire.has(loaded, OTHER));
        ItemStack plainLoaded = Wire.saveAndReload(level, Wire.plain());
        expect("S5 a plain stack stays plain through save and reload", Wire.carriesNothing(plainLoaded) && Wire.same(plainLoaded, Wire.plain()));
    }

    /** S5 (sync): the stack as a client receives it from the network reads the same state and is the same stack. */
    private static void sync(StackCarrier<ItemStack> items, Level level) throws Exception {
        ItemStack stack = Wire.plain();
        items.set(stack, NAME, "Wolf");
        items.set(stack, MARK, Unit.INSTANCE);
        items.set(stack, PAIR, new Pair("p", 1));
        ItemStack client = Wire.sync(level, stack);
        expect("S5 a synced stack is the same stack", Wire.same(stack, client) && Wire.equal(stack, client));
        expect("S5 a synced stack reads the same", "Wolf".equals(items.get(client, NAME).orElse(null)) && items.has(client, MARK)
                && new Pair("p", 1).equals(items.get(client, PAIR).orElse(null)));
        items.clear(stack, NAME);
        items.clear(stack, MARK);
        items.clear(stack, PAIR);
        expect("S5 a plain stack is plain on the client", Wire.carriesNothing(Wire.sync(level, stack)) && Wire.same(Wire.sync(level, stack), Wire.plain()));
    }

    // ---------------------------------------------------------------------------------------------------- old keys

    /** The migration on a real stack: old keys read as components, and the next write stores them fenced and removes them. */
    private static void oldKeys(StackCarrier<ItemStack> items) {
        ItemStack stack = Wire.plain();
        Wire.putInt(stack, OLD_COUNT, 9);
        expect("old keys: read as a component", Integer.valueOf(9).equals(items.get(stack, COUNT).orElse(null)));
        items.set(stack, NAME, "moved");
        expect("old keys: the next write removes the old key", !Wire.has(stack, OLD_COUNT));
        expect("old keys: and keeps the value fenced", Integer.valueOf(9).equals(items.get(stack, COUNT).orElse(null)));
    }

    // ---------------------------------------------------------------------------------------------------- S6

    /** S6: an empty stack carries nothing and a write to it changes nothing; a write that changes nothing leaves the stack. */
    private static void emptyAndUnchanged(StackCarrier<ItemStack> items) {
        ItemStack empty = ItemStack.EMPTY;
        expect("S6 an empty stack reads empty", items.read(empty).components().isEmpty() && !items.has(empty, MARK));
        WriteOutcome out = items.set(empty, NAME, "x");
        expect("S6 a write to an empty stack changes nothing", out instanceof WriteOutcome.Written w && !w.changed());
        expect("S6 the empty stack is still empty and plain", empty.isEmpty() && Wire.carriesNothing(empty));
        ItemStack stack = Wire.plain();
        items.set(stack, NAME, "same");
        ItemStack before = Wire.copy(stack);
        WriteOutcome again = items.set(stack, NAME, "same");
        expect("S6 setting what is already there changes nothing", again instanceof WriteOutcome.Written w && !w.changed());
        expect("S6 and leaves the stack as it was", Wire.equal(before, stack) && Wire.rootText(before).equals(Wire.rootText(stack)));
    }

    // ---------------------------------------------------------------------------------------------------- checks

    private static void written(WriteOutcome outcome, String what) {
        expect(what + " is written", outcome.isWritten());
    }

    private static void expect(String what, boolean holds) {
        if (!holds) {
            throw new AssertionError("item-carrier conformance: " + what);
        }
    }
}
