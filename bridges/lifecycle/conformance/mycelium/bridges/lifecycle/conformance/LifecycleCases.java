package mycelium.bridges.lifecycle.conformance;

import mycelium.item_component.v1.model.ComponentCodec;
import mycelium.item_component.v1.model.TagNode;
import mycelium.state_lifecycle.v1.model.ComponentSlotCodec;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.LifecyclePort;
import mycelium.state_lifecycle.v1.model.Policy;
import mycelium.state_lifecycle.v1.model.Reading;
import mycelium.state_lifecycle.v1.model.Shape;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StaleHostException;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Transitions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Conformance for {@link LifecyclePort} on a real game (the cases G1 to G8 of {@code state.lifecycle@1}, SPEC section 5): the cases a
 * mod imports into its game tests. They hold the Threshold's laws to what the running game and the loader do, by driving the game's own
 * server paths with mock players (a player is placed with {@code PlayerList.placeNewPlayer}, removed with {@code PlayerList.remove},
 * replaced with {@code PlayerList.respawn}, killed with {@code die}, taken to another dimension with the portal's own teleport), so that
 * the loader's event for each fires for real and the port under test hears it. They are written against the port, not against the
 * bridge, so they apply to the bridge's port and to a mod's own override alike ({@link #cases(Subject)}); {@code LifecycleConformance}
 * is the bridge's own, with the cases of its internals.
 *
 * <p>A case is a name and a body that throws {@link AssertionError} when a law fails. Registering them is the mod's: each loader registers
 * game tests its own way, so a mod runs them from whatever test it has, handing each the level it runs in:
 *
 * <pre>
 * for (LifecycleConformance.Case c : LifecycleConformance.cases()) {          // an override: LifecycleCases.cases(mySubject)
 *     register("lifecycle_" + c.name(), helper -&gt; c.body().run(helper.getLevel()));
 * }
 * </pre>
 *
 * <p>The cases declare slots of their own ({@link #registry()}) and use only mock players of their own, whom they remove. What a case
 * cannot drive is said in its comment: the server's stop is not driven (the test server is running), so the case ends the hosts as the
 * stop does, through {@link Open#stop()}, and the loader's stopping event is exercised only by the server really stopping.
 */
public class LifecycleCases {
    protected LifecycleCases() {
    }

    /** The namespace of the slots and the transition the cases declare. */
    public static final String NAMESPACE = "mycelium_conformance";

    /** The cases' own transition: a player drifts (same host, same incarnation). */
    public static final TransitionId DRIFT = new TransitionId(NAMESPACE + ":drift");

    public static final Transitions TRANSITIONS = Transitions.builder().declare(NAMESPACE + ":drift", Shape.STAYS, HostKind.PLAYER).build();

    /** A session value kept through a respawn, a death and a dimension change; dropped at every end. */
    public static final Slot<String> KEPT = session("kept", "", Policy.keep(), Policy.keep(), Policy.keep());
    /** A session value a respawn drops. */
    public static final Slot<String> DROPPED_AT_RESPAWN = session("dropped_at_respawn", "", Policy.drop(), Policy.keep(), Policy.keep());
    /** A session value a respawn resets to its initial value, 7. */
    public static final Slot<Integer> RESET_AT_RESPAWN = session("reset_at_respawn", 7, Policy.reset(), Policy.keep(), Policy.keep());
    /** A session value a death drops. */
    public static final Slot<String> DROPPED_AT_DEATH = session("dropped_at_death", "", Policy.keep(), Policy.drop(), Policy.keep());
    /** A session count the cases' own transition raises by one. */
    public static final Slot<Integer> DRIFTED = session("drifted", 0, Policy.keep(), Policy.keep(), Policy.migrate(n -> n + 1));
    /** A durable value kept at every crossing. */
    public static final Slot<String> CHARM = durable("charm", Policy.keep());
    /** A durable value a death drops, live and stored. */
    public static final Slot<String> FRAGILE = durable("fragile", Policy.drop());
    /** A session value of the server. */
    public static final Slot<String> SERVER_NOTE = Slot.<String>session(NAMESPACE + ":server_note", HostKind.SERVER, "")
            .on(TRANSITIONS.shaped(HostKind.SERVER, Shape.SAVES, Shape.BEGINS), Policy.keep())
            .on(TRANSITIONS.shaped(HostKind.SERVER, Shape.ENDS), Policy.drop()).build();
    /** A durable value of the server. */
    public static final Slot<String> SERVER_STAMP = Slot.durable(NAMESPACE + ":server_stamp", HostKind.SERVER, "", ComponentSlotCodec.of(ComponentCodec.STRING))
            .on(TRANSITIONS.shaped(HostKind.SERVER, Shape.SAVES, Shape.BEGINS, Shape.ENDS), Policy.<String>keep()).build();

    private static <T> Slot<T> session(String name, T initial, Policy<T> atRespawn, Policy<T> atDeath, Policy<T> atDrift) {
        return Slot.<T>session(NAMESPACE + ":" + name, HostKind.PLAYER, initial)
                .on(TRANSITIONS.shaped(HostKind.PLAYER, Shape.SAVES, Shape.BEGINS), Policy.keep())
                .on(TRANSITIONS.shaped(HostKind.PLAYER, Shape.ENDS, Shape.FORKS), Policy.drop())
                .on(TransitionId.DIMENSION_CHANGE, Policy.keep())
                .on(TransitionId.RESPAWN, atRespawn)
                .on(TransitionId.DEATH, atDeath)
                .on(DRIFT, atDrift)
                .build();
    }

    private static Slot<String> durable(String name, Policy<String> atDeath) {
        return Slot.durable(NAMESPACE + ":" + name, HostKind.PLAYER, "", ComponentSlotCodec.of(ComponentCodec.STRING))
                .on(TRANSITIONS.shaped(HostKind.PLAYER, Shape.SAVES, Shape.BEGINS, Shape.ENDS, Shape.SUCCEEDS), Policy.keep())
                .on(TransitionId.DIMENSION_CHANGE, Policy.keep())
                .on(TransitionId.DEATH, atDeath)
                .on(DRIFT, Policy.keep())
                .on(TRANSITIONS.shaped(HostKind.PLAYER, Shape.FORKS), Policy.copy())
                .build();
    }

    /** The registry the cases run under: the slots above, checked by the contract's own declaration check. */
    public static SlotRegistry registry() {
        return SlotRegistry.of(TRANSITIONS, List.of(KEPT, DROPPED_AT_RESPAWN, RESET_AT_RESPAWN, DROPPED_AT_DEATH, DRIFTED, CHARM, FRAGILE,
                SERVER_NOTE, SERVER_STAMP));
    }

    /** One case: what it is called and what it checks. */
    public record Case(String name, Body body) {
    }

    @FunctionalInterface
    public interface Body {
        /** Throws {@link AssertionError} when a law fails. {@code level} is the level the game test runs in. */
        void run(Level level) throws Exception;
    }

    /** A port under test, listening to the game's events until it is closed. */
    public interface Open extends AutoCloseable {
        LifecyclePort<ServerPlayer, MinecraftServer> port();

        /**
         * Ends every live host as the server's stop does (each saves, then ends; then the server), without stopping the server. The loader's
         * stopping event is not driven: a test server is running.
         */
        void stop();

        /**
         * A port over what the world would hold after a restart: the saved data written and read back, with no one live. Empty when the
         * implementation cannot say, and the case that wants it then checks what it can without it.
         */
        Optional<LifecyclePort<ServerPlayer, MinecraftServer>> reloaded();

        @Override
        void close();
    }

    /** How a case gets the port under test: the bridge's, or a mod's own override. */
    @FunctionalInterface
    public interface Subject {
        /** A port over {@code server} for {@code registry}, attached to the game's events, as the mod would have made it. */
        Open open(MinecraftServer server, SlotRegistry registry);
    }

    /**
     * The cases, for the port {@code subject} makes: the bridge's own (what {@code LifecycleConformance.cases()} passes) or a mod's override.
     * Nothing here mentions the bridge's source, so a mod that overrides the bridge imports these and holds its own class to the cases.
     */
    public static List<Case> cases(Subject subject) {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("g1_login_gives_a_current_ref", level -> with(subject, level, LifecycleCases::login)));
        cases.add(new Case("g2_logout_ends_the_incarnation", level -> with(subject, level, LifecycleCases::logout)));
        cases.add(new Case("g3_respawn_succeeds_the_incarnation", level -> with(subject, level, LifecycleCases::respawn)));
        cases.add(new Case("g4_death_and_dimension_change_stay", level -> with(subject, level, LifecycleCases::stays)));
        cases.add(new Case("g5_durable_survives_relog_save_and_reload", level -> with(subject, level, LifecycleCases::durable)));
        cases.add(new Case("g6_deferred_work_never_reaches_the_new_player", level -> with(subject, level, LifecycleCases::deferred)));
        cases.add(new Case("g7_server_stop_ends_everything", level -> with(subject, level, LifecycleCases::serverStop)));
        cases.add(new Case("g8_own_transitions", level -> with(subject, level, LifecycleCases::ownTransitions)));
        return cases;
    }

    public static void expect(String what, boolean holds) {
        if (!holds) {
            throw new AssertionError(what);
        }
    }

    // ---------------------------------------------------------------------------------------------------- the rig of a case

    /** What a case holds: the port, the level, and the players it made, whom it removes at the end. */
    public static final class Ctx {
        public final ServerLevel level;
        public final MinecraftServer server;
        public final Open open;
        public final LifecyclePort<ServerPlayer, MinecraftServer> port;
        private final List<ServerPlayer> made = new ArrayList<>();

        Ctx(ServerLevel level, Open open) {
            this.level = level;
            this.server = level.getServer();
            this.open = open;
            this.port = open.port();
        }

        /** A new player joins, as a player does: placed in the world by the game's own player list. */
        public ServerPlayer join(String name) {
            return join(UUID.randomUUID(), name);
        }

        public ServerPlayer join(UUID id, String name) {
            ServerPlayer player = Wire.join(level, id, name);
            made.add(player);
            return player;
        }

        /** The player leaves, as the game removes a player on a disconnect. */
        public void leave(ServerPlayer player) {
            Wire.leave(player);
        }

        /** The game replaces the player with a new object, as it does after a death or the End. */
        public ServerPlayer respawn(ServerPlayer player) {
            ServerPlayer fresh = Wire.respawn(player);
            made.add(fresh);
            return fresh;
        }

        void cleanUp() {
            for (ServerPlayer player : made) {
                if (Wire.online(player)) {
                    try {
                        Wire.leave(player);
                    } catch (RuntimeException e) {
                        // a test player that cannot be removed is not this case's failure to report
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface Run {
        void run(Ctx c) throws Exception;
    }

    private static void with(Subject subject, Level level, Run body) throws Exception {
        ServerLevel server = (ServerLevel) level;
        Open open = subject.open(server.getServer(), registry());
        Ctx ctx = new Ctx(server, open);
        try {
            body.run(ctx);
        } finally {
            ctx.cleanUp();
            open.close();
        }
    }

    // ---------------------------------------------------------------------------------------------------- the cases

    /** G1: a player who joins has a current ref, which resolves to that very object; a session slot is absent. */
    private static void login(Ctx c) {
        ServerPlayer player = c.join("g1");
        Optional<HostRef> ref = c.port.current(player);
        expect("a player who joins has a current ref", ref.isPresent());
        expect("the ref resolves to the player who joined: " + ref, c.port.players().resolve(ref.get()).orElse(null) == player);
        expect("the host is the player's uuid: " + ref.get().host(), ref.get().host().equals(c.port.player(player.getUUID().toString())));
        expect("a new player's first incarnation is the first: " + ref.get(), ref.get().incarnation().value() == 1);
        expect("the port knows the host as the player's current: " + ref, c.port.players().current(ref.get().host()).equals(ref));
        expect("a session slot starts absent", c.port.players().peek(KEPT, ref.get()).isAbsent());
        expect("and reads as its initial value", "".equals(c.port.players().read(KEPT, ref.get()).valueOrNull()));
        expect("the server is a host too, current from its start", c.port.currentServer().isPresent()
                && c.port.server().resolve(c.port.currentServer().get()).orElse(null) == c.server);
        expect("a player object nobody joined with is no host", c.port.current(Wire.stranger(c.level)).isEmpty());
    }

    /** G2: after the player leaves, the old ref resolves to nothing and no session slot of theirs holds a value; coming back is a later incarnation. */
    private static void logout(Ctx c) {
        UUID id = UUID.randomUUID();
        ServerPlayer player = c.join(id, "g2");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        c.port.write(KEPT, ref, "held");
        c.port.write(CHARM, ref, "charm");
        expect("both values are held while the player is on", c.port.players().cellSlots(ref).size() == 2);
        c.leave(player);
        expect("after the player left the old ref resolves to nothing", c.port.players().resolve(ref).isEmpty());
        expect("the object that left is no longer current", c.port.current(player).isEmpty());
        expect("the host is not live", c.port.players().current(ref.host()).isEmpty());
        expect("no session slot of the player holds a value, and no live copy of a durable one (L4): " + c.port.players().cellSlots(ref),
                c.port.players().cellSlots(ref).isEmpty());
        expect("a write through the old ref reaches nothing", c.port.write(KEPT, ref, "after").isGone());
        expect("the durable value is kept for next time", c.port.players().stored(ref.host(), CHARM.id()).isPresent());
        ServerPlayer again = c.join(id, "g2");
        HostRef second = c.port.current(again).orElseThrow(() -> new AssertionError("no ref after the rejoin"));
        expect("coming back is a later incarnation of the same host: " + ref + " then " + second,
                second.host().equals(ref.host()) && second.incarnation().value() > ref.incarnation().value());
        expect("the session value did not come back", c.port.players().peek(KEPT, second).isAbsent());
        expect("the old ref is still nothing", c.port.players().resolve(ref).isEmpty());
    }

    /** G3: the old object is no longer current; the new one is the next incarnation of the same host, each slot having done its respawn policy. */
    private static void respawn(Ctx c) {
        ServerPlayer player = c.join("g3");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        c.port.write(KEPT, ref, "carried");
        c.port.write(DROPPED_AT_RESPAWN, ref, "dropped");
        c.port.write(RESET_AT_RESPAWN, ref, 99);
        c.port.write(CHARM, ref, "charm");
        ServerPlayer fresh = c.respawn(player);
        expect("the game made a new player object", fresh != player);
        expect("the old object is no longer current", c.port.current(player).isEmpty());
        expect("the old ref resolves to nothing", c.port.players().resolve(ref).isEmpty());
        HostRef next = c.port.current(fresh).orElseThrow(() -> new AssertionError("the new object has no ref"));
        expect("the new object is the next incarnation of the same host: " + ref + " then " + next,
                next.host().equals(ref.host()) && next.incarnation().equals(ref.incarnation().next()));
        expect("it resolves to the new object", c.port.players().resolve(next).orElse(null) == fresh);
        expect("a kept value is carried", "carried".equals(c.port.players().peek(KEPT, next).valueOrNull()));
        expect("a dropped value is gone", c.port.players().peek(DROPPED_AT_RESPAWN, next).isAbsent());
        expect("a reset value is its initial value", Integer.valueOf(7).equals(c.port.players().peek(RESET_AT_RESPAWN, next).valueOrNull()));
        expect("a durable value is carried", "charm".equals(c.port.players().peek(CHARM, next).valueOrNull()));
        expect("nothing of the old incarnation is held: " + c.port.players().cellSlots(ref), c.port.players().cellSlots(ref).isEmpty());
        expect("a write through the old ref reaches nothing, and not the new object", c.port.write(KEPT, ref, "stale").isGone()
                && "carried".equals(c.port.players().peek(KEPT, next).valueOrNull()));
    }

    /** G4: death and a dimension change keep the incarnation and apply their policies. */
    private static void stays(Ctx c) {
        ServerPlayer player = c.join("g4");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        c.port.write(KEPT, ref, "kept");
        c.port.write(DROPPED_AT_DEATH, ref, "fragile");
        c.port.write(FRAGILE, ref, "durable fragile");
        c.port.write(CHARM, ref, "charm");
        Wire.travel(player, Wire.otherDimension(c.server));
        expect("a dimension change is the same incarnation: " + ref + " and " + c.port.current(player), c.port.current(player).equals(Optional.of(ref)));
        expect("and the values are as they were", "kept".equals(c.port.players().peek(KEPT, ref).valueOrNull())
                && "fragile".equals(c.port.players().peek(DROPPED_AT_DEATH, ref).valueOrNull()));
        Wire.die(player);
        expect("a death is the same incarnation: " + ref + " and " + c.port.current(player), c.port.current(player).equals(Optional.of(ref)));
        expect("a session value a death drops is gone", c.port.players().peek(DROPPED_AT_DEATH, ref).isAbsent());
        expect("a durable value a death drops is gone, live and stored", c.port.players().peek(FRAGILE, ref).isAbsent()
                && c.port.players().stored(ref.host(), FRAGILE.id()).isEmpty());
        expect("the rest are kept", "kept".equals(c.port.players().peek(KEPT, ref).valueOrNull())
                && "charm".equals(c.port.players().peek(CHARM, ref).valueOrNull()));
    }

    /** G5: a durable value survives a logout and a login, a save and a reload of the saved data, and a respawn that keeps it. */
    private static void durable(Ctx c) {
        UUID id = UUID.randomUUID();
        ServerPlayer player = c.join(id, "g5");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        c.port.write(CHARM, ref, "survives");
        c.port.write(SERVER_STAMP, c.port.currentServer().orElseThrow(() -> new AssertionError("no server host")), "stamped");
        c.leave(player);
        expect("a logout keeps the stored form", c.port.players().stored(ref.host(), CHARM.id()).isPresent());
        ServerPlayer again = c.join(id, "g5");
        HostRef second = c.port.current(again).orElseThrow(() -> new AssertionError("no ref after the rejoin"));
        expect("a login loads it again, equal: " + c.port.players().peek(CHARM, second), "survives".equals(c.port.players().peek(CHARM, second).valueOrNull()));
        c.port.write(CHARM, second, "survives a save");
        c.port.fire(TransitionId.SAVE, second);
        Optional<LifecyclePort<ServerPlayer, MinecraftServer>> reloaded = c.open.reloaded();
        if (reloaded.isPresent()) {
            expect("a save and a reload of the saved data keep the value, equal: " + reloaded.get().players().stored(second.host(), CHARM.id()),
                    reloaded.get().players().stored(second.host(), CHARM.id()).equals(c.port.players().stored(second.host(), CHARM.id()))
                            && reloaded.get().players().stored(second.host(), CHARM.id()).isPresent());
            expect("and the server's own durable value", reloaded.get().server().stored(c.port.serverHost(), SERVER_STAMP.id()).isPresent()
                    && "stamped".equals(c.port.server().peek(SERVER_STAMP, c.port.currentServer().get()).valueOrNull()));
            expect("nothing is live in the reloaded world: " + reloaded.get().players().live(), reloaded.get().players().live().isEmpty());
        }
        ServerPlayer fresh = c.respawn(again);
        expect("a respawn that keeps the value carries it", "survives a save".equals(c.port.players().peek(CHARM, c.port.current(fresh).get()).valueOrNull()));
        expect("and the stored form is the last one saved", c.port.players().stored(second.host(), CHARM.id()).isPresent());
    }

    /** G6: work holding a ref from before a relog or a respawn resolves to nothing; it never reaches the new player object. */
    private static void deferred(Ctx c) {
        UUID id = UUID.randomUUID();
        ServerPlayer player = c.join(id, "g6");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        List<ServerPlayer> reached = new ArrayList<>();
        Runnable task = () -> c.port.players().resolve(ref).ifPresent(reached::add);
        task.run();
        expect("while the player is on, the task reaches them", reached.size() == 1 && reached.get(0) == player);
        reached.clear();
        c.leave(player);
        ServerPlayer again = c.join(id, "g6");
        task.run();
        expect("after a relog the task reaches no one, and not the new object", reached.isEmpty());
        HostRef second = c.port.current(again).orElseThrow(() -> new AssertionError("no ref after the rejoin"));
        expect("a write the task made through the old ref is refused, and the new player is untouched", c.port.write(KEPT, ref, "stale").isGone()
                && c.port.players().peek(KEPT, second).isAbsent());
        Runnable second_task = () -> c.port.players().resolve(second).ifPresent(reached::add);
        ServerPlayer fresh = c.respawn(again);
        second_task.run();
        expect("a respawn leaves the task that held the old incarnation reaching no one", reached.isEmpty());
        expect("the new object is reached by a ref taken after the respawn", c.port.players().resolve(c.port.current(fresh).get()).orElse(null) == fresh);
    }

    /** G7: stopping saves, then ends every live player and the server: nothing session-scoped is held and the durable values are stored. */
    private static void serverStop(Ctx c) {
        ServerPlayer one = c.join("g7a");
        ServerPlayer two = c.join("g7b");
        HostRef first = c.port.current(one).orElseThrow(() -> new AssertionError("no ref after the join"));
        HostRef second = c.port.current(two).orElseThrow(() -> new AssertionError("no ref after the join"));
        HostRef server = c.port.currentServer().orElseThrow(() -> new AssertionError("no server host"));
        c.port.write(KEPT, first, "a");
        c.port.write(KEPT, second, "b");
        c.port.write(CHARM, first, "charm a");
        c.port.write(SERVER_NOTE, server, "note");
        c.port.write(SERVER_STAMP, server, "stamp");
        var players = c.port.players();
        var serverStore = c.port.server();
        c.open.stop();
        expect("no player is live: " + players.live(), players.live().isEmpty());
        expect("the server is not live: " + serverStore.live(), serverStore.live().isEmpty());
        expect("the refs resolve to nothing", players.resolve(first).isEmpty() && players.resolve(second).isEmpty() && serverStore.resolve(server).isEmpty());
        expect("no session value is held by any of them (L4)", players.cellSlots(first).isEmpty() && players.cellSlots(second).isEmpty()
                && serverStore.cellSlots(server).isEmpty());
        expect("the durable values are stored", players.stored(first.host(), CHARM.id()).isPresent() && serverStore.stored(server.host(), SERVER_STAMP.id()).isPresent());
    }

    /** G8: the mod's own transition applies its policies to a live host, and a stale ref is refused. */
    private static void ownTransitions(Ctx c) {
        ServerPlayer player = c.join("g8");
        HostRef ref = c.port.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
        c.port.write(DRIFTED, ref, 5);
        c.port.fire(DRIFT, ref);
        expect("the transition's policy ran: " + c.port.players().peek(DRIFTED, ref), Integer.valueOf(6).equals(c.port.players().peek(DRIFTED, ref).valueOrNull()));
        expect("a transition of the stays shape keeps the incarnation", c.port.current(player).equals(Optional.of(ref)));
        Reading<Integer> updated = c.port.update(DRIFTED, ref, n -> n * 10);
        expect("update writes f of the value: " + updated, Integer.valueOf(60).equals(updated.valueOrNull()));
        c.leave(player);
        boolean refused = false;
        try {
            c.port.fire(DRIFT, ref);
        } catch (StaleHostException e) {
            refused = true;
        }
        expect("a stale ref is refused by name", refused);
    }

    /** The host id of a player: a helper for the bridge's own cases. */
    protected static HostId idOf(LifecyclePort<ServerPlayer, MinecraftServer> port, ServerPlayer player) {
        return port.player(player.getUUID().toString());
    }

    /** A stored form of the wrong shape for {@link #CHARM}: a number where a string belongs, for the cases of a bridge's own quarantine. */
    protected static TagNode wrongForm() {
        return TagNode.TagInt.of(5);
    }
}
