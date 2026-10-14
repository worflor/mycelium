package mycelium.storage_access.v1.harness;

import mycelium.flow.v1.model.FlowTransaction;
import mycelium.flow.v1.model.Receipt;
import mycelium.flow.v1.model.Transfer;
import mycelium.flow.v1.model.TransferOutcome;
import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.harness.Suite;
import mycelium.storage_access.v1.fixtures.Block;
import mycelium.storage_access.v1.fixtures.MemoryContainer;
import mycelium.storage_access.v1.fixtures.MemoryTank;
import mycelium.storage_access.v1.fixtures.MemoryWorld;
import mycelium.storage_access.v1.fixtures.Providers;
import mycelium.storage_access.v1.model.Face;
import mycelium.storage_access.v1.model.FluidUnit;
import mycelium.storage_access.v1.model.Goods;
import mycelium.storage_access.v1.model.Holding;
import mycelium.storage_access.v1.model.Kind;
import mycelium.storage_access.v1.model.Move;
import mycelium.storage_access.v1.model.MoveExecutor;
import mycelium.storage_access.v1.model.MoveOutcome;
import mycelium.storage_access.v1.model.MoveReceipt;
import mycelium.storage_access.v1.model.Position;
import mycelium.storage_access.v1.model.StoragePort;
import mycelium.storage_access.v1.model.Store;
import mycelium.storage_access.v1.model.StoreAddress;
import mycelium.storage_access.v1.model.Stores;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;

import static mycelium.storage_access.v1.fixtures.Catalog.BUCKET;
import static mycelium.storage_access.v1.fixtures.Catalog.COAL;
import static mycelium.storage_access.v1.fixtures.Catalog.DROPLETS;
import static mycelium.storage_access.v1.fixtures.Catalog.GOLD;
import static mycelium.storage_access.v1.fixtures.Catalog.IRON;
import static mycelium.storage_access.v1.fixtures.Catalog.MILLIBUCKETS;
import static mycelium.storage_access.v1.fixtures.Catalog.NAMED_IRON;
import static mycelium.storage_access.v1.fixtures.Catalog.NETHER;
import static mycelium.storage_access.v1.fixtures.Catalog.OVERWORLD;
import static mycelium.storage_access.v1.fixtures.Catalog.PEARL;
import static mycelium.storage_access.v1.fixtures.Catalog.WATER;

/**
 * The fixtures of {@code storage.access@1} (SPEC §8), run against one executor and one port built over a memory world. The
 * reference is {@code StorageAccess::move} over {@code MemoryPort} with the reference adapters; each matrix entry swaps one
 * of them for a broken one.
 */
public final class StorageAccessContractTests {
    private static final int SEEDS = 40;
    private static final int STEPS = 12;
    private static final List<Goods> GOODS = List.of(IRON, GOLD, COAL, PEARL, NAMED_IRON, WATER);

    private final MoveExecutor executor;
    private final Function<MemoryWorld, StoragePort> ports;

    public StorageAccessContractTests(MoveExecutor executor, Function<MemoryWorld, StoragePort> ports) {
        this.executor = executor;
        this.ports = ports;
    }

    public Check run() {
        return new Suite("storage.access@1")
                .add("T1", this::t1Items)
                .add("T2", this::t2Fluids)
                .add("T3", this::t3AntiEvasion)
                .add("T4", this::t4Edges)
                .add("T5", this::t5Lookup)
                .add("T6", this::t6Sided)
                .add("T7", this::t7Capacity)
                .add("T8", this::t8SimulationIsPure)
                .add("T9", this::t9ReleaseRestores)
                .add("T10", this::t10BothOrNeither)
                .add("T11", this::t11Remainder)
                .add("T12", this::t12ExactUnits)
                .add("T13", this::t13Vanish)
                .add("T14", this::t14NestedInTransfer)
                .add("T15", this::t15Units)
                .add("T16", this::t16Seeded)
                .run();
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static Position at(int x) {
        return new Position(x, 64, 0);
    }

    private static StoreAddress addr(String level, int x, Face face) {
        return new StoreAddress(level, at(x), face);
    }

    private static StoreAddress ow(int x, Face face) {
        return addr(OVERWORLD, x, face);
    }

    private static StoreAddress ow(int x) {
        return ow(x, Face.UP);
    }

    private static long moved(MoveOutcome outcome) {
        return outcome instanceof MoveOutcome.Moved m ? m.receipt().moved() : -1;
    }

    private static String describe(MoveOutcome outcome) {
        if (outcome instanceof MoveOutcome.Moved m) {
            return "Moved(" + m.receipt().moved() + ")";
        }
        if (outcome instanceof MoveOutcome.Rejected r) {
            return "Rejected(" + r.reason() + ")";
        }
        if (outcome instanceof MoveOutcome.Stale s) {
            return "Stale(" + s.vanished().position() + ")";
        }
        return "Unsupported";
    }

    private MoveOutcome move(StoragePort port, StoreAddress from, StoreAddress to, Goods goods, long amount,
                             FlowTransaction tx, FeedbackSink sink) {
        return executor.move(port, new Move(from, to, goods, amount), tx, sink);
    }

    /** One move in its own outermost transaction, committed. */
    private MoveOutcome committed(StoragePort port, StoreAddress from, StoreAddress to, Goods goods, long amount, FeedbackSink sink) {
        FlowTransaction tx = FlowTransaction.open();
        MoveOutcome out = move(port, from, to, goods, amount, tx, sink);
        tx.commit();
        return out;
    }

    private static Store find(StoragePort port, StoreAddress address, Kind kind) {
        return port.find(address, kind).orElseThrow(() -> new IllegalStateException("no " + kind + " store at " + address));
    }

    private static boolean contentsAre(Store store, List<Holding> expected) {
        return store.contents().equals(expected);
    }

    // ---- T1 to T3: positive obligations --------------------------------------------------------------------------

    /** P1, A1, F1: twelve iron asked of a chest of twenty into a slot with room for seven. */
    private void t1Items(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer a = w.place(OVERWORLD, at(0), MemoryContainer.chest(27).with(0, IRON, 20).with(1, GOLD, 5));
        MemoryContainer b = w.place(OVERWORLD, at(1), MemoryContainer.chest(1).with(0, IRON, 57));
        StoragePort port = ports.apply(w);
        FeedbackSink sink = new FeedbackSink();
        FlowTransaction tx = FlowTransaction.open();
        MoveOutcome out = move(port, ow(0, Face.EAST), ow(1, Face.WEST), IRON, 12, tx, sink);
        c.equalLong(7, moved(out), "P1:moved-min");
        c.that(sink.events().isEmpty(), "F1:feedback-at-outer-commit");
        tx.commit();
        c.that(a.count(IRON) == 13 && b.count(IRON) == 64 && a.count(GOLD) == 5, "A1:moved-both");
        c.that(sink.events().size() == 1 && sink.events().get(0).moved() == 7, "F1:feedback-once");
    }

    /** P1, A1, S5: half a bucket from a Fabric tank (droplets) to a Forge tank (millibuckets), and back. */
    private void t2Fluids(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryTank f = w.place(OVERWORLD, at(2), MemoryTank.fabric(8).with(WATER, 81_000));
        MemoryTank g = w.place(OVERWORLD, at(3), MemoryTank.forge(4));
        StoragePort port = ports.apply(w);
        BigInteger total = w.total(WATER);
        MoveOutcome out = committed(port, ow(2), ow(3), WATER, 500, new FeedbackSink());
        c.equalLong(500, moved(out), "P1:moved-min");
        c.that(f.amount() == 40_500 && g.amount() == 500, "A1:moved-both");
        c.equal(total, w.total(WATER), "S5:native-conserved");
        MoveOutcome back = committed(port, ow(3), ow(2), WATER, 1500, new FeedbackSink());
        c.equalLong(500, moved(back), "P1:moved-min");
        c.that(f.amount() == 81_000 && g.amount() == 0, "A1:moved-both");
        c.equal(total, w.total(WATER), "S5:native-conserved");
    }

    /** P2: an admissible move of items and one of fluids, each on a port that reaches both kinds, must move. */
    private void t3AntiEvasion(Check c) {
        MemoryWorld w = new MemoryWorld();
        w.place(OVERWORLD, at(0), MemoryContainer.chest(9).with(0, IRON, 20));
        w.place(OVERWORLD, at(1), MemoryContainer.chest(9));
        w.place(OVERWORLD, at(2), MemoryTank.fabric(2).with(WATER, 81_000));
        w.place(OVERWORLD, at(3), MemoryTank.forge(2));
        StoragePort port = ports.apply(w);
        for (Move m : List.of(new Move(ow(0), ow(1), IRON, 5), new Move(ow(2), ow(3), WATER, 250))) {
            FlowTransaction tx = FlowTransaction.open();
            MoveOutcome out = executor.move(port, m, tx, new FeedbackSink());
            tx.commit();
            c.that(!(out instanceof MoveOutcome.Unsupported), "P2:no-evasion");
            c.that(!(out instanceof MoveOutcome.Rejected), "P2:no-evasion");
            c.that(out instanceof MoveOutcome.Moved, "P2:no-evasion");
        }
    }

    // ---- T4: edge semantics --------------------------------------------------------------------------------------

    /** E1: each refusal names its reason, and leaves the world and the feedback untouched even when the caller commits. */
    private void t4Edges(Check c) {
        MemoryWorld w = new MemoryWorld();
        w.place(OVERWORLD, at(0), MemoryContainer.chest(9).with(0, IRON, 20));
        w.place(OVERWORLD, at(1), MemoryContainer.chest(9));
        w.place(OVERWORLD, at(2), MemoryTank.fabric(2).with(WATER, 81_000));
        w.place(OVERWORLD, at(3), MemoryTank.forge(2));
        w.place(OVERWORLD, at(4), MemoryContainer.chest(1).with(0, GOLD, 64));
        StoragePort port = ports.apply(w);
        record Case(StoragePort port, Move move, String expected) {
        }
        List<Case> cases = List.of(
                new Case(port, new Move(ow(0), ow(1), IRON, 0), "Rejected(ZERO_REQUEST)"),
                new Case(port, new Move(ow(0, Face.UP), ow(0, Face.NORTH), IRON, 5), "Rejected(SAME_STORE)"),
                new Case(port, new Move(ow(9), ow(1), IRON, 5), "Rejected(NO_SOURCE)"),
                new Case(port, new Move(ow(0), ow(9), IRON, 5), "Rejected(NO_DESTINATION)"),
                new Case(port, new Move(ow(0), ow(3), WATER, 100), "Rejected(NO_SOURCE)"),
                new Case(port, new Move(addr(NETHER, 0, Face.UP), ow(1), IRON, 5), "Rejected(NO_SOURCE)"),
                new Case(port, new Move(ow(1), ow(0), IRON, 5), "Rejected(EMPTY_SOURCE)"),
                new Case(port, new Move(ow(0), ow(4), IRON, 5), "Rejected(FULL_DESTINATION)"),
                new Case(Providers.itemsOnly(port), new Move(ow(2), ow(3), WATER, 100), "Unsupported"));
        for (Case k : cases) {
            Object before = w.snapshot();
            FeedbackSink sink = new FeedbackSink();
            FlowTransaction tx = FlowTransaction.open();
            MoveOutcome out = executor.move(k.port(), k.move(), tx, sink);
            tx.commit();
            c.equal(k.expected(), describe(out), "E1:outcome");
            c.that(before.equals(w.snapshot()) && sink.events().isEmpty(), "E1:outcome");
        }
    }

    // ---- T5 to T9: what every store keeps ------------------------------------------------------------------------

    /** S1: found at its own level and position, answering for the face asked; nothing where nothing stands; fresh after a replace. */
    private void t5Lookup(Check c) {
        MemoryWorld w = new MemoryWorld();
        w.place(OVERWORLD, at(0), MemoryContainer.chest(9).with(0, IRON, 20));
        w.place(NETHER, at(0), MemoryContainer.chest(9).with(0, GOLD, 3));
        w.place(OVERWORLD, at(7), MemoryContainer.chest(9).with(0, COAL, 2));
        MemoryContainer furnace = w.place(OVERWORLD, at(5), MemoryContainer.furnace());
        w.place(OVERWORLD, at(6), MemoryContainer.chest(9).with(0, IRON, 5));
        StoragePort port = ports.apply(w);

        Optional<Store> top = port.find(ow(5, Face.UP), Kind.ITEM);
        c.that(top.isPresent() && top.get().address().face() == Face.UP, "S1:face-kept");
        if (top.isPresent()) {
            FlowTransaction tx = FlowTransaction.open();
            long put = top.get().insert(IRON, 3, tx);
            tx.commit();
            c.that(put == 3 && furnace.countAt(0) == 3, "S1:face-kept");
        }

        Optional<Store> nether = port.find(addr(NETHER, 0, Face.UP), Kind.ITEM);
        c.that(nether.isPresent() && contentsAre(nether.get(), List.of(new Holding(GOLD, 3))), "S1:level-kept");
        c.that(port.find(addr(NETHER, 7, Face.UP), Kind.ITEM).isEmpty(), "S1:level-kept");

        c.that(port.find(ow(9), Kind.ITEM).isEmpty(), "S1:absent");
        c.that(port.find(ow(0), Kind.FLUID).isEmpty(), "S1:absent");

        Optional<Store> before = port.find(ow(6), Kind.ITEM);
        c.that(before.isPresent(), "S1:fresh-after-replace");
        w.remove(OVERWORLD, at(6));
        c.that(port.find(ow(6), Kind.ITEM).isEmpty(), "S1:absent");
        w.place(OVERWORLD, at(6), MemoryContainer.chest(9).with(0, GOLD, 3));
        Optional<Store> after = port.find(ow(6), Kind.ITEM);
        c.that(after.isPresent() && after.get().present() && contentsAre(after.get(), List.of(new Holding(GOLD, 3))),
                "S1:fresh-after-replace");
    }

    /** S4: a furnace (input on top, fuel from the sides, output from below) and an offering plate, face by face. */
    private void t6Sided(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer furnace = w.place(OVERWORLD, at(5),
                MemoryContainer.furnace().with(0, IRON, 5).with(1, COAL, 3).with(2, GOLD, 4));
        MemoryContainer plate = w.place(OVERWORLD, at(8), MemoryContainer.plate().with(0, GOLD, 2).with(1, IRON, 4));
        w.place(OVERWORLD, at(1), MemoryContainer.chest(9));
        StoragePort port = ports.apply(w);

        Store bottom = find(port, ow(5, Face.DOWN), Kind.ITEM);
        c.that(contentsAre(bottom, List.of(new Holding(GOLD, 4))), "S4:takes-only-allowed");
        FlowTransaction t1 = FlowTransaction.open();
        c.that(bottom.extract(IRON, 10, t1) == 0 && bottom.extract(COAL, 10, t1) == 0, "S4:takes-only-allowed");
        c.that(bottom.extract(GOLD, 10, t1) == 4, "S4:takes-only-allowed");
        t1.abort();

        Store side = find(port, ow(5, Face.NORTH), Kind.ITEM);
        FlowTransaction t2 = FlowTransaction.open();
        c.that(side.insert(IRON, 5, t2) == 0, "S4:places-only-allowed");
        c.that(side.insert(COAL, 5, t2) == 5 && furnace.countAt(1) == 8, "S4:places-only-allowed");
        Store topFace = find(port, ow(5, Face.UP), Kind.ITEM);
        c.that(topFace.insert(IRON, 2, t2) == 2 && furnace.countAt(0) == 7, "S4:places-only-allowed");
        c.that(furnace.countAt(2) == 4, "S4:places-only-allowed");
        t2.abort();

        Store shelf = find(port, addr(OVERWORLD, 8, Face.NORTH), Kind.ITEM);
        c.that(contentsAre(shelf, List.of(new Holding(IRON, 4))), "S4:takes-only-allowed");
        FlowTransaction t3 = FlowTransaction.open();
        c.that(shelf.extract(GOLD, 2, t3) == 0 && plate.countAt(0) == 2, "S4:takes-only-allowed");
        c.that(shelf.insert(IRON, 10, t3) == 0, "S4:places-only-allowed");
        c.that(shelf.insert(GOLD, 3, t3) == 3 && plate.countAt(0) == 5, "S4:places-only-allowed");
        boolean shelfKept = true;
        for (int s = 2; s < 10; s++) {
            shelfKept &= plate.countAt(s) == 0;
        }
        c.that(shelfKept && plate.countAt(1) == 4, "S4:places-only-allowed");
        t3.abort();

        MoveOutcome offering = committed(port, ow(8, Face.WEST), ow(1), GOLD, 2, new FeedbackSink());
        c.equal("Rejected(EMPTY_SOURCE)", describe(offering), "S4:takes-only-allowed");
        c.that(plate.countAt(0) == 2, "S4:takes-only-allowed");
    }

    /** S7: ender pearls stack to sixteen, whatever the container's own limit. */
    private void t7Capacity(Check c) {
        MemoryWorld w = new MemoryWorld();
        w.place(OVERWORLD, at(10), MemoryContainer.chest(3).with(0, PEARL, 16).with(1, PEARL, 16).with(2, PEARL, 8));
        MemoryContainer into = w.place(OVERWORLD, at(11), MemoryContainer.chest(2));
        StoragePort port = ports.apply(w);
        c.equalLong(32, find(port, ow(11), Kind.ITEM).simulateInsert(PEARL, 40), "S7:within-capacity");
        MoveOutcome out = committed(port, ow(10), ow(11), PEARL, 40, new FeedbackSink());
        c.equalLong(32, moved(out), "S7:within-capacity");
        c.that(into.countAt(0) <= 16 && into.countAt(1) <= 16, "S7:within-capacity");
    }

    /** S2: reading and simulating every store at every face changes nothing anywhere. */
    private void t8SimulationIsPure(Check c) {
        MemoryWorld w = new MemoryWorld();
        w.place(OVERWORLD, at(0), MemoryContainer.chest(9).with(0, IRON, 20).with(3, GOLD, 7));
        w.place(OVERWORLD, at(5), MemoryContainer.furnace().with(0, IRON, 5).with(1, COAL, 3).with(2, GOLD, 4));
        w.place(OVERWORLD, at(8), MemoryContainer.plate().with(0, GOLD, 2).with(1, IRON, 4));
        w.place(OVERWORLD, at(2), MemoryTank.fabric(2).with(WATER, 100_000));
        w.place(OVERWORLD, at(24), MemoryTank.cauldron().with(WATER, 54_000));
        StoragePort port = ports.apply(w);
        Object before = w.snapshot();
        for (int x : new int[] {0, 5, 8, 2, 24}) {
            for (Face face : Face.values()) {
                for (Kind kind : Kind.values()) {
                    Optional<Store> s = port.find(ow(x, face), kind);
                    if (s.isEmpty()) {
                        continue;
                    }
                    s.get().contents();
                    for (Goods g : GOODS) {
                        s.get().simulateExtract(g, 1000);
                        s.get().simulateInsert(g, 1000);
                    }
                }
            }
        }
        c.that(before.equals(w.snapshot()), "S2:simulate-mutates-nothing");
    }

    /** S3: a release puts every slot and every tank back exactly as it was. */
    private void t9ReleaseRestores(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer d = w.place(OVERWORLD, at(12), MemoryContainer.chest(4).with(2, IRON, 10).with(3, GOLD, 3));
        MemoryTank f = w.place(OVERWORLD, at(2), MemoryTank.fabric(2).with(WATER, 81_000));
        StoragePort port = ports.apply(w);
        Object before = d.snapshot();
        Store chest = find(port, ow(12), Kind.ITEM);
        FlowTransaction tx = FlowTransaction.open();
        c.equalLong(4, chest.extract(IRON, 4, tx), "S3:abort-restores");
        chest.insert(GOLD, 20, tx);
        tx.abort();
        c.equal(before, d.snapshot(), "S3:abort-restores");

        Store tank = find(port, ow(2), Kind.FLUID);
        FlowTransaction tx2 = FlowTransaction.open();
        c.equalLong(300, tank.extract(WATER, 300, tx2), "S3:abort-restores");
        tx2.abort();
        c.equalLong(81_000, f.amount(), "S3:abort-restores");
    }

    // ---- T10 to T14: the move --------------------------------------------------------------------------------------

    /** A1, F1: a move nested in a scope that commits, under an outermost transaction that aborts, then one that commits. */
    private void t10BothOrNeither(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer a = w.place(OVERWORLD, at(0), MemoryContainer.chest(27).with(0, IRON, 20));
        MemoryContainer b = w.place(OVERWORLD, at(1), MemoryContainer.chest(1).with(0, IRON, 57));
        StoragePort port = ports.apply(w);

        FeedbackSink sink = new FeedbackSink();
        FlowTransaction outer = FlowTransaction.open();
        FlowTransaction nested = outer.openNested();
        move(port, ow(0), ow(1), IRON, 12, nested, sink);
        nested.commit();
        c.that(sink.events().isEmpty(), "F1:feedback-at-outer-commit");
        outer.abort();
        c.that(a.count(IRON) == 20 && b.count(IRON) == 57, "A1:neither-unless-moved");
        c.that(sink.events().isEmpty(), "F1:feedback-at-outer-commit");

        FeedbackSink sink2 = new FeedbackSink();
        FlowTransaction outer2 = FlowTransaction.open();
        FlowTransaction nested2 = outer2.openNested();
        MoveOutcome out = move(port, ow(0), ow(1), IRON, 12, nested2, sink2);
        nested2.commit();
        c.that(sink2.events().isEmpty(), "F1:feedback-at-outer-commit");
        outer2.commit();
        c.that(a.count(IRON) == 13 && b.count(IRON) == 64 && moved(out) == 7, "A1:moved-both");
        c.that(sink2.events().size() == 1 && sink2.events().get(0).moved() == 7, "F1:feedback-once");
    }

    /** A1: a destination that promises seven and takes four; one that promises seven and takes none. */
    private void t11Remainder(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer a = w.place(OVERWORLD, at(0), MemoryContainer.chest(27).with(0, IRON, 20));
        MemoryContainer b = w.place(OVERWORLD, at(1), MemoryContainer.chest(1).with(0, IRON, 60));
        MemoryContainer full = w.place(OVERWORLD, at(4), MemoryContainer.chest(1).with(0, IRON, 64));
        StoragePort port = Providers.overpromising(Providers.overpromising(ports.apply(w), ow(1), 3), ow(4), 7);
        FeedbackSink sink = new FeedbackSink();
        MoveOutcome out = committed(port, ow(0), ow(1), IRON, 12, sink);
        c.that(moved(out) == 4 && b.count(IRON) == 64, "A1:moved-both");
        c.that(sink.events().size() == 1 && sink.events().get(0).moved() == b.count(IRON) - 60, "A1:moved-both");
        c.equalLong(16, a.count(IRON), "A1:remainder-released");

        Object before = w.snapshot();
        MoveOutcome none = committed(port, ow(0), ow(4), IRON, 12, new FeedbackSink());
        c.equal("Rejected(INSERTION_FAILED)", describe(none), "A1:neither-unless-moved");
        c.that(before.equals(w.snapshot()) && full.count(IRON) == 64, "A1:neither-unless-moved");
    }

    /** S5: droplets that are not a whole thousandth stay; a cauldron's third of a bucket is refused; a whole bucket moves. */
    private void t12ExactUnits(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryTank h = w.place(OVERWORLD, at(20), MemoryTank.fabric(1).with(WATER, 100));
        MemoryTank g = w.place(OVERWORLD, at(21), MemoryTank.forge(1));
        MemoryTank k = w.place(OVERWORLD, at(22), MemoryTank.fabric(1).with(WATER, 60));
        MemoryTank g2 = w.place(OVERWORLD, at(23), MemoryTank.forge(1));
        MemoryTank cauldron = w.place(OVERWORLD, at(24), MemoryTank.cauldron().with(WATER, 81_000));
        MemoryTank f2 = w.place(OVERWORLD, at(25), MemoryTank.fabric(8));
        StoragePort port = ports.apply(w);
        BigInteger total = w.total(WATER);

        MoveOutcome one = committed(port, ow(20), ow(21), WATER, 1000, new FeedbackSink());
        c.that(moved(one) == 1 && h.amount() == 19 && g.amount() == 1, "S5:native-conserved");
        c.equal(total, w.total(WATER), "S5:native-conserved");

        MoveOutcome less = committed(port, ow(22), ow(23), WATER, 1000, new FeedbackSink());
        c.equal("Rejected(EMPTY_SOURCE)", describe(less), "S5:native-conserved");
        c.that(k.amount() == 60 && g2.amount() == 0, "S5:native-conserved");
        c.equal(total, w.total(WATER), "S5:native-conserved");

        MoveOutcome third = committed(port, ow(24), ow(25), WATER, 667, new FeedbackSink());
        c.that(!(third instanceof MoveOutcome.Moved), "S5:remainder-refused");
        c.that(cauldron.amount() == 81_000 && f2.amount() == 0, "S5:remainder-refused");
        c.equal(total, w.total(WATER), "S5:native-conserved");

        MoveOutcome bucket = committed(port, ow(24), ow(25), WATER, 1000, new FeedbackSink());
        c.equalLong(1000, moved(bucket), "P1:moved-min");
        c.that(cauldron.amount() == 0 && f2.amount() == 81_000, "P1:moved-min");
        c.equal(total, w.total(WATER), "S5:native-conserved");
    }

    /** S6: a handle whose store vanished does nothing; a store that vanishes inside a move releases it. */
    private void t13Vanish(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer e = w.place(OVERWORLD, at(30), MemoryContainer.chest(9).with(0, IRON, 5));
        MemoryTank t = w.place(OVERWORLD, at(31), MemoryTank.fabric(2).with(WATER, 81_000));
        w.place(OVERWORLD, at(32), MemoryContainer.chest(27).with(0, IRON, 20));
        MemoryContainer b = w.place(OVERWORLD, at(33), MemoryContainer.chest(1));
        MemoryContainer a2 = w.place(OVERWORLD, at(34), MemoryContainer.chest(27).with(0, IRON, 20));
        w.place(OVERWORLD, at(35), MemoryContainer.chest(1));
        w.place(OVERWORLD, at(36), MemoryTank.fabric(2).with(WATER, 81_000));
        MemoryTank g4 = w.place(OVERWORLD, at(37), MemoryTank.forge(2));
        StoragePort port = ports.apply(w);

        Store chest = find(port, ow(30), Kind.ITEM);
        Store tank = find(port, ow(31), Kind.FLUID);
        w.remove(OVERWORLD, at(30));
        w.remove(OVERWORLD, at(31));
        Object sealed = w.snapshot();
        c.that(!chest.present() && !tank.present(), "S6:vanished-moves-nothing");
        c.that(chest.contents().isEmpty() && tank.contents().isEmpty(), "S6:vanished-moves-nothing");
        c.that(chest.simulateExtract(IRON, 5) == 0 && chest.simulateInsert(GOLD, 5) == 0
                && tank.simulateExtract(WATER, 100) == 0, "S6:vanished-moves-nothing");
        FlowTransaction tx = FlowTransaction.open();
        long took = chest.extract(IRON, 5, tx) + chest.insert(GOLD, 5, tx) + tank.extract(WATER, 100, tx);
        tx.commit();
        c.that(took == 0 && sealed.equals(w.snapshot()) && e.count(IRON) == 5 && t.amount() == 81_000,
                "S6:vanished-moves-nothing");

        record Trap(Block block, StoreAddress from, StoreAddress to, Goods goods, long amount) {
        }
        for (Trap trap : List.of(
                new Trap(b, ow(32), ow(33), IRON, 12),
                new Trap(a2, ow(34), ow(35), IRON, 12),
                new Trap(g4, ow(36), ow(37), WATER, 400))) {
            trap.block().vanishOnNextWrite();
            Object before = w.snapshot();
            FeedbackSink sink = new FeedbackSink();
            FlowTransaction stroke = FlowTransaction.open();
            MoveOutcome out = move(port, trap.from(), trap.to(), trap.goods(), trap.amount(), stroke, sink);
            stroke.commit();
            trap.block().disarm();
            c.that(out instanceof MoveOutcome.Stale, "S6:vanish-aborts");
            c.that(before.equals(w.snapshot()) && sink.events().isEmpty(), "S6:vanish-aborts");
        }
    }

    /** A1 through inventory.transfer@1: two stores as its providers, moved by its own Transfer.execute in one transaction. */
    private void t14NestedInTransfer(Check c) {
        MemoryWorld w = new MemoryWorld();
        MemoryContainer a = w.place(OVERWORLD, at(0), MemoryContainer.chest(27).with(0, IRON, 20));
        MemoryContainer b = w.place(OVERWORLD, at(1), MemoryContainer.chest(1).with(0, IRON, 57));
        StoragePort port = ports.apply(w);
        List<Receipt> receipts = new ArrayList<>();
        FlowTransaction tx = FlowTransaction.open();
        TransferOutcome out = Transfer.execute(Stores.backend(find(port, ow(0), Kind.ITEM)),
                Stores.backend(find(port, ow(1), Kind.ITEM)), IRON.variant(), 12, tx, receipts::add);
        tx.commit();
        c.that(out instanceof TransferOutcome.Committed t && t.receipt().committed() == 7, "A1:moved-both");
        c.that(a.count(IRON) == 13 && b.count(IRON) == 64 && receipts.size() == 1, "A1:moved-both");
    }

    /** The declared unit's arithmetic: droplets and millibuckets convert exactly, a remainder converts to nothing. */
    private void t15Units(Check c) {
        c.equal(OptionalLong.of(40_500), DROPLETS.toNative(500), "U1:exact");
        c.equal(OptionalLong.of(500), DROPLETS.toDeclared(40_500), "U1:exact");
        c.equal(OptionalLong.empty(), DROPLETS.toDeclared(100), "U1:exact");
        c.equalLong(1, DROPLETS.wholeDeclared(100), "U1:exact");
        c.equalLong(0, DROPLETS.wholeDeclared(80), "U1:exact");
        c.equal(OptionalLong.of(27), MILLIBUCKETS.toNative(27), "U1:exact");
        FluidUnit thirds = new FluidUnit(3);
        c.that(thirds.declaredStep() == 1000 && thirds.nativeStep() == 3, "U1:exact");
        c.equal(OptionalLong.empty(), thirds.toNative(500), "U1:exact");
        c.equalLong(1000, thirds.exactAtMost(1500), "U1:exact");
        for (long n = 0; n < 500; n++) {
            OptionalLong d = DROPLETS.toDeclared(n);
            c.that(d.isEmpty() || DROPLETS.toNative(d.getAsLong()).getAsLong() == n, "U1:exact");
        }
    }

    // ---- T16: seeded property ------------------------------------------------------------------------------------

    private record Site(String level, int x, String what) {
    }

    private static Block fresh(String what) {
        return switch (what) {
            case "chest9" -> MemoryContainer.chest(9);
            case "chest3" -> MemoryContainer.chest(3);
            case "furnace" -> MemoryContainer.furnace();
            case "plate" -> MemoryContainer.plate();
            case "fabric" -> MemoryTank.fabric(4);
            case "forge" -> MemoryTank.forge(4);
            default -> MemoryTank.cauldron();
        };
    }

    private static void fill(Block block, Seeded r) {
        List<Goods> items = List.of(IRON, GOLD, COAL, PEARL, NAMED_IRON);
        if (block instanceof MemoryContainer m) {
            if (m.isPlate()) {
                m.with(0, GOLD, r.nextInt(0, 6));
                for (int s = 1; s < 10; s++) {
                    if (r.nextInt(3) == 0) {
                        m.with(s, items.get(r.nextInt(items.size())), r.nextInt(1, 9));
                    }
                }
                return;
            }
            for (int s = 0; s < m.size(); s++) {
                if (r.nextInt(3) != 0) {
                    Goods g = items.get(r.nextInt(items.size()));
                    m.with(s, g, r.nextInt(1, 17));
                }
            }
        } else if (block instanceof MemoryTank t) {
            if (t.quantum() > 1) {
                t.with(WATER, 27_000L * r.nextInt(0, 4));
            } else if (t.unit().equals(DROPLETS)) {
                t.with(WATER, r.nextInt(0, 3) * 81_000L + r.nextInt(0, 400));
            } else {
                t.with(WATER, r.nextInt(0, 3000));
            }
        }
    }

    private static long measure(Block block, Goods goods) {
        if (block instanceof MemoryContainer m) {
            return m.count(goods);
        }
        return ((MemoryTank) block).amountOf(goods);
    }

    private static long nativeOf(Block block, long declared) {
        if (block instanceof MemoryTank t) {
            return t.unit().toNative(declared).orElse(-1);
        }
        return declared;
    }

    /** A1, F1, S4, S5, S6, S7 after every step of seeded worlds: random moves, nesting, aborts and stores that vanish. */
    private void t16Seeded(Check c) {
        List<Site> sites = List.of(new Site(OVERWORLD, 0, "chest9"), new Site(OVERWORLD, 1, "chest3"),
                new Site(OVERWORLD, 2, "furnace"), new Site(OVERWORLD, 3, "plate"), new Site(OVERWORLD, 4, "fabric"),
                new Site(OVERWORLD, 5, "forge"), new Site(OVERWORLD, 6, "cauldron"), new Site(NETHER, 0, "chest9"),
                new Site(NETHER, 1, "fabric"));
        for (int seed = 0; seed < SEEDS; seed++) {
            Seeded r = new Seeded(0x5701_A9E5L + seed);
            MemoryWorld w = new MemoryWorld();
            Map<Site, Block> blocks = new LinkedHashMap<>();
            for (Site s : sites) {
                Block b = fresh(s.what());
                fill(b, r);
                blocks.put(s, w.place(s.level(), at(s.x()), b));
            }
            StoragePort port = ports.apply(w);
            for (int step = 0; step < STEPS; step++) {
                Site from = sites.get(r.nextInt(sites.size()));
                Site to = sites.get(r.nextInt(sites.size()));
                Goods goods = GOODS.get(r.nextInt(GOODS.size()));
                long amount = goods.kind() == Kind.FLUID ? r.nextInt(0, 2500) : r.nextInt(0, 80);
                Face fromFace = Face.values()[r.nextInt(6)];
                Face toFace = Face.values()[r.nextInt(6)];
                Block source = blocks.get(from);
                Block destination = blocks.get(to);
                Block trapped = r.nextInt(8) == 0 ? (r.nextBoolean() ? source : destination) : null;
                if (trapped != null) {
                    trapped.vanishOnNextWrite();
                }
                boolean nest = r.nextBoolean();
                boolean commit = r.nextInt(5) != 0;

                Object before = w.snapshot();
                Map<Goods, BigInteger> totals = new LinkedHashMap<>();
                for (Goods g : GOODS) {
                    totals.put(g, w.total(g));
                }
                long sourceBefore = measure(source, goods);
                long destinationBefore = measure(destination, goods);
                MemoryContainer plate = (MemoryContainer) blocks.get(sites.get(3));
                long offeringBefore = plate.countAt(0);
                long[] shelfBefore = new long[10];
                for (int s = 1; s < 10; s++) {
                    shelfBefore[s] = plate.countAt(s);
                }
                MemoryContainer furnace = (MemoryContainer) blocks.get(sites.get(2));
                long outputBefore = furnace.countAt(2);

                FeedbackSink sink = new FeedbackSink();
                FlowTransaction outer = FlowTransaction.open();
                FlowTransaction scope = nest ? outer.openNested() : outer;
                MoveOutcome out = move(port, addr(from.level(), from.x(), fromFace), addr(to.level(), to.x(), toFace),
                        goods, amount, scope, sink);
                if (nest) {
                    scope.commit();
                }
                if (commit) {
                    outer.commit();
                } else {
                    outer.abort();
                }
                if (trapped != null) {
                    trapped.disarm();
                }

                String where = " (seed " + seed + " step " + step + " " + describe(out) + ")";
                for (Goods g : GOODS) {
                    if (!totals.get(g).equals(w.total(g))) {
                        c.fail((g.kind() == Kind.FLUID ? "S5:native-conserved" : "A1:neither-unless-moved") + where);
                    }
                }
                if (out instanceof MoveOutcome.Moved m && commit) {
                    long n = m.receipt().moved();
                    c.that(sourceBefore - measure(source, goods) == nativeOf(source, n)
                            && measure(destination, goods) - destinationBefore == nativeOf(destination, n), "A1:moved-both");
                    c.that(sink.events().size() == 1 && sink.events().get(0).equals(m.receipt()), "F1:feedback-once");
                } else {
                    if (out instanceof MoveOutcome.Stale) {
                        c.that(before.equals(w.snapshot()), "S6:vanish-aborts");
                    } else {
                        c.that(before.equals(w.snapshot()), "A1:neither-unless-moved");
                    }
                    c.that(sink.events().isEmpty(), "F1:feedback-at-outer-commit");
                }
                c.that(plate.countAt(0) >= offeringBefore, "S4:takes-only-allowed");
                boolean shelfKept = true;
                for (int s = 1; s < 10; s++) {
                    shelfKept &= plate.countAt(s) <= shelfBefore[s];
                }
                c.that(shelfKept && furnace.countAt(2) <= outputBefore, "S4:places-only-allowed");
                for (Block b : w.blocks()) {
                    if (b instanceof MemoryContainer m) {
                        for (int s = 0; s < m.size(); s++) {
                            Optional<Holding> held = m.at(s);
                            c.that(held.isEmpty() || held.get().amount() <= m.limit(s, held.get().goods()), "S7:within-capacity");
                        }
                    }
                }

                for (Map.Entry<Site, Block> e : blocks.entrySet()) {
                    if (!e.getValue().present()) {
                        Block replacement = fresh(e.getKey().what());
                        fill(replacement, r);
                        e.setValue(w.place(e.getKey().level(), at(e.getKey().x()), replacement));
                    }
                }
            }
        }
    }
}
