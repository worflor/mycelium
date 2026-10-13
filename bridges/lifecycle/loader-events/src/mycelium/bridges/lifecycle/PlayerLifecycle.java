package mycelium.bridges.lifecycle;

import com.mojang.logging.LogUtils;
import mycelium.item_component.v1.model.TagNode;
import mycelium.state_lifecycle.v1.model.Crossing;
import mycelium.state_lifecycle.v1.model.CrossingException;
import mycelium.state_lifecycle.v1.model.Durability;
import mycelium.state_lifecycle.v1.model.HostId;
import mycelium.state_lifecycle.v1.model.HostKind;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.HostStore;
import mycelium.state_lifecycle.v1.model.LifecyclePort;
import mycelium.state_lifecycle.v1.model.Plan;
import mycelium.state_lifecycle.v1.model.Slot;
import mycelium.state_lifecycle.v1.model.SlotCodecException;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import mycelium.state_lifecycle.v1.model.StandardCrossings;
import mycelium.state_lifecycle.v1.model.TransitionId;
import mycelium.state_lifecycle.v1.model.Tx;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minecraft's players and server as a {@code state.lifecycle@1} {@link LifecyclePort}: the loader's events turned into the
 * contract's crossings (decision 0016, the Threshold's bridge). The mod gives it its {@link SlotRegistry} and calls
 * {@link #install()} once; from then on every login, logout, respawn, death, dimension change, save and server stop of a player,
 * and the start, save and stop of the server, is planned and applied by the contract's engine in a transaction of its own.
 *
 * <p>The loader's events are the loader overlay's ({@code LoaderEvents}), and reach this class through {@link Hooks}, which a mod on a
 * loader the bridge has no half for can call itself. Durable slots live in the world's saved data ({@link LifecycleData}), whatever the
 * loader. Everything happens on the server thread.
 *
 * <p>A failed crossing never reaches the game: it is logged, naming the host and transition, and the game goes on, since a loader's
 * event callback that throws would drop a player or stop the server. A durable slot whose stored form does not decode at a login is
 * quarantined (kept, apart, in the world's data) and the login is tried again without it, so a bad value costs that value and
 * never the player.
 */
public final class PlayerLifecycle implements LifecyclePort<ServerPlayer, MinecraftServer> {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SlotRegistry registry;
    private final String dataName;
    private final boolean durable;

    // what one run of a server binds; before a server is attached, and after it stops, the stores are empty and hold nothing
    private Bound bound;
    private Store<ServerPlayer> players;
    private Store<MinecraftServer> serverStore;

    private PlayerLifecycle(SlotRegistry registry, String dataName) {
        this.registry = registry;
        this.dataName = dataName;
        boolean any = false;
        for (Slot<?> slot : registry.slots()) {
            if (slot.durability() == Durability.DURABLE) {
                any = true;
                requireTagForm(slot);
            }
        }
        this.durable = any;
        detach();
    }

    /**
     * A lifecycle over the mod's slots. It does nothing until {@link #install()} (and a server) give it events. Its durable values are
     * kept in the world's saved data under a name made from this class's own package, which is the mod's, so two mods that take the
     * bridge never share a file.
     */
    public static PlayerLifecycle of(SlotRegistry registry) {
        return of(registry, Hooks.class.getPackageName().replace('.', '_'));
    }

    /**
     * As {@link #of(SlotRegistry)}, with the name of the saved-data file chosen by the mod: lower case letters, digits and underscores. A name
     * that does not change when the mod's packages are moved keeps a world's durable values with the mod.
     */
    public static PlayerLifecycle of(SlotRegistry registry, String dataName) {
        if (!dataName.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("the saved-data name is lower case letters, digits and underscores, not '" + dataName + "'");
        }
        return new PlayerLifecycle(registry, dataName);
    }

    /** A durable slot's stored form must be the {@link TagNode} the world saves: found out here, when the mod starts, not at the first logout. */
    private static <T> void requireTagForm(Slot<T> slot) {
        Object form = slot.codec().orElseThrow(() -> new IllegalArgumentException("durable slot " + slot.id() + " has no codec")).encode(slot.initial());
        if (!(form instanceof TagNode)) {
            throw new IllegalArgumentException("durable slot " + slot.id() + " stores a " + form.getClass().getName() + ": the lifecycle bridge saves"
                    + " item.component@1 TagNode forms, which a slot made with ComponentSlotCodec stores");
        }
    }

    /**
     * Wires this lifecycle to the loader's events: the first lifecycle installed registers the loader's listeners (the half the build's
     * loader overlay adds, {@code LoaderEvents}), and every installed lifecycle hears them. Safe to call again.
     */
    public PlayerLifecycle install() {
        Hooks.install(this);
        return this;
    }

    /**
     * Ends every live host as the server's stop does: each player saves and ends ({@code mycelium:server_stop}), then the server, and the
     * lifecycle lets go of the world. The loader's stopping event calls this; so does a test that cannot stop the server it runs in.
     */
    public void stop() {
        MinecraftServer server = bound == null ? null : bound.server;
        if (server != null) {
            stopping(server);
        }
    }

    /** Takes this lifecycle out of the game's events, after ending what it holds as a server stop does. Another can be installed later. */
    public void close() {
        stop();
        Hooks.remove(this);
    }

    // ---------------------------------------------------------------------------------------------------- the port

    @Override
    public SlotRegistry registry() {
        return registry;
    }

    @Override
    public HostStore<ServerPlayer> players() {
        return players;
    }

    @Override
    public HostStore<MinecraftServer> server() {
        return serverStore;
    }

    @Override
    public HostId player(String token) {
        return new HostId(HostKind.PLAYER, Saved.WORLD, token);
    }

    @Override
    public HostId serverHost() {
        return new HostId(HostKind.SERVER, Saved.WORLD, "server");
    }

    @Override
    public Optional<HostRef> current(ServerPlayer player) {
        return players.currentOf(player);
    }

    @Override
    public Optional<HostRef> currentServer() {
        return serverStore.current(serverHost());
    }

    @Override
    public Store<?> storeFor(HostKind kind) {
        switch (kind) {
            case PLAYER:
                return players;
            case SERVER:
                return serverStore;
            default:
                throw new IllegalArgumentException("the lifecycle bridge keeps players and the server, not " + kind + " hosts");
        }
    }

    @Override
    public void fire(TransitionId transition, HostRef host) {
        stay(storeFor(host.host().kind()), transition, host);
    }

    @Override
    public void cross(TransitionId transition, Crossing<ServerPlayer> crossing) {
        run(players, transition, crossing);
    }

    /** The server this lifecycle is attached to, while it is. */
    public Optional<MinecraftServer> attached() {
        return bound == null ? Optional.empty() : Optional.of(bound.server);
    }

    /** How many stored forms the world has set apart because they did not decode (kept, never used): zero when nothing was ever bad. */
    public int quarantinedForms() {
        return bound != null && bound.data != null ? bound.data.saved().quarantinedCount() : 0;
    }

    /** How many value cells the players and the server hold, of any incarnation: zero once everything has ended (L4). */
    public int heldCells() {
        return players.cellCount() + serverStore.cellCount();
    }

    // ---------------------------------------------------------------------------------------------------- binding to a server

    /**
     * Binds to a running server: its world's saved data is read, the stores are made over it, and the server host begins
     * ({@code mycelium:load}). The loader's server-started event does it; so does the first login if the event was not seen. A different
     * server in the same JVM ends the first one's hosts.
     */
    public void attach(MinecraftServer server) {
        if (bound != null && bound.server == server) {
            return;
        }
        if (bound != null) {
            stopping(bound.server);
        }
        LifecycleData data = LifecycleData.of(server, dataName);
        Runnable flush = this::persistAll;
        data.beforeSave(flush);
        bound = new Bound(server, data, flush);
        players = new Store<>(data.saved(), data::changed);
        serverStore = new Store<>(data.saved(), data::changed);
        data.alwaysSave(durable);
        guard("the server's start", () -> begin(serverStore, TransitionId.LOAD, serverHost(), server));
    }

    /** What the world would hold after a restart, with no one in it: the saved data written and read back, under this lifecycle's slots. */
    public PlayerLifecycle reloaded() {
        if (bound == null) {
            throw new IllegalStateException("not attached to a server: there is nothing saved to reload");
        }
        persistAll();
        PlayerLifecycle again = new PlayerLifecycle(registry, dataName);
        Saved saved = LifecycleData.roundTrip(bound.server, bound.data.saved());
        again.players = new Store<>(saved, () -> { });
        again.serverStore = new Store<>(saved, () -> { });
        again.bound = new Bound(bound.server, null, null);
        MinecraftServer server = bound.server;
        again.guard("the server's start", () -> again.begin(again.serverStore, TransitionId.LOAD, again.serverHost(), server));
        return again;
    }

    private void detach() {
        Saved empty = new Saved();
        players = new Store<>(empty, () -> { });
        serverStore = new Store<>(empty, () -> { });
        bound = null;
    }

    private record Bound(MinecraftServer server, LifecycleData data, Runnable flush) {
    }

    // ---------------------------------------------------------------------------------------------------- the platform's crossings

    void onServerStarted(MinecraftServer server) {
        guard("the server's start", () -> attach(server));
    }

    void onLogin(ServerPlayer player) {
        guard("a login", () -> {
            MinecraftServer server = Game.server(player);
            if (server == null) {
                return;
            }
            attach(server);
            HostId id = player(player.getUUID().toString());
            Optional<HostRef> live = players.current(id);
            if (live.isPresent()) {
                if (players.resolve(live.get()).orElse(null) == player) {
                    return; // the same object, told twice
                }
                // a logout that was never seen: the old object's incarnation ends before the new one begins
                saveAndEnd(players, live.get(), TransitionId.LOGOUT);
            }
            begin(players, TransitionId.LOGIN, id, player);
        });
    }

    void onLogout(ServerPlayer player) {
        guard("a logout", () -> {
            Optional<HostRef> ref = players.currentOf(player);
            if (ref.isPresent()) {
                saveAndEnd(players, ref.get(), TransitionId.LOGOUT);
            }
        });
    }

    /** The loader's "copy from the old player": the host's incarnation passes from the old object to the new one. */
    void onRespawn(ServerPlayer old, ServerPlayer fresh) {
        guard("a respawn", () -> {
            MinecraftServer server = Game.server(fresh);
            if (server != null) {
                attach(server);
            }
            HostId id = player(fresh.getUUID().toString());
            Optional<HostRef> live = players.current(id);
            if (live.isPresent()) {
                run(players, TransitionId.RESPAWN, new Crossing.Succeed<>(live.get(), fresh));
            } else {
                // the game made a player this lifecycle never saw join: it begins now, as it would at a login
                begin(players, TransitionId.LOGIN, id, fresh);
            }
        });
    }

    void onDeath(ServerPlayer player) {
        guard("a death", () -> players.currentOf(player).ifPresent(ref -> stay(players, TransitionId.DEATH, ref)));
    }

    void onDimensionChange(ServerPlayer player) {
        guard("a dimension change", () -> players.currentOf(player).ifPresent(ref -> stay(players, TransitionId.DIMENSION_CHANGE, ref)));
    }

    /** The world is being saved: every live host's durable values are written (the engine's {@code mycelium:save}). */
    void onSave(MinecraftServer server) {
        if (bound != null && bound.server == server) {
            guard("a save", this::persistAll);
        }
    }

    /** The server is stopping: each live player, then the server, saves and ends ({@code mycelium:server_stop}). */
    void onServerStopping(MinecraftServer server) {
        if (bound != null && bound.server == server) {
            guard("the server's stop", () -> stopping(server));
        }
    }

    private void stopping(MinecraftServer server) {
        if (bound == null || bound.server != server) {
            return;
        }
        for (HostRef ref : new ArrayList<>(players.live())) {
            saveAndEnd(players, ref, TransitionId.SERVER_STOP);
        }
        for (HostRef ref : new ArrayList<>(serverStore.live())) {
            saveAndEnd(serverStore, ref, TransitionId.SERVER_STOP);
        }
        if (bound.data != null) {
            bound.data.removeBeforeSave(bound.flush);
            bound.data.alwaysSave(false);
        }
        detach();
    }

    // ---------------------------------------------------------------------------------------------------- crossings

    /** Every live host saves ({@code mycelium:save}): its durable values are encoded into the world's forms. */
    private void persistAll() {
        for (HostRef ref : new ArrayList<>(players.live())) {
            stay(players, TransitionId.SAVE, ref);
        }
        for (HostRef ref : new ArrayList<>(serverStore.live())) {
            stay(serverStore, TransitionId.SAVE, ref);
        }
    }

    /** An end writes nothing, so a host that ends saves first (the contract's section 4); a save that fails does not keep the host alive. */
    private <H> void saveAndEnd(Store<H> store, HostRef ref, TransitionId end) {
        try {
            stay(store, TransitionId.SAVE, ref);
        } catch (RuntimeException e) {
            LOGGER.error("Mycelium lifecycle: saving {} before {} failed; it ends without that save", ref, end, e);
        }
        run(store, end, new Crossing.End<>(ref));
    }

    private <H> void stay(Store<H> store, TransitionId transition, HostRef ref) {
        run(store, transition, new Crossing.Stay<>(ref));
    }

    /** A host comes to exist. A stored value that does not decode is quarantined and the crossing tried again without it. */
    private <H> void begin(Store<H> store, TransitionId transition, HostId id, H handle) {
        try {
            run(store, transition, new Crossing.Begin<>(id, handle));
        } catch (CrossingException first) {
            List<SlotId> bad = undecodable(store, id);
            if (bad.isEmpty()) {
                throw first;
            }
            for (SlotId slot : bad) {
                LOGGER.error("Mycelium lifecycle: the stored value of {} for {} does not decode ({}); it is kept apart (quarantined) and {} starts without it",
                        slot, id, first.getMessage(), id);
                store.quarantine(id, slot);
            }
            run(store, transition, new Crossing.Begin<>(id, handle));
        }
    }

    private List<SlotId> undecodable(Store<?> store, HostId id) {
        List<SlotId> bad = new ArrayList<>();
        for (Slot<?> slot : registry.slotsFor(id.kind())) {
            if (slot.durability() == Durability.DURABLE && store.stored(id, slot.id()).isPresent() && !decodes(slot, store.stored(id, slot.id()).get())) {
                bad.add(slot.id());
            }
        }
        return bad;
    }

    private static boolean decodes(Slot<?> slot, Object form) {
        try {
            slot.codec().orElseThrow().decode(form);
            return true;
        } catch (SlotCodecException | RuntimeException e) {
            return false;
        }
    }

    /** One crossing: planned by the contract's engine and applied in a transaction that aborts on any failure (L8). */
    private <H> void run(Store<H> store, TransitionId transition, Crossing<H> crossing) {
        try (Tx tx = Tx.open()) {
            Plan<H> plan = StandardCrossings.INSTANCE.plan(transition, crossing, store, registry);
            StandardCrossings.INSTANCE.apply(plan, store, tx);
            tx.commit();
        }
    }

    /** A crossing that fails is the mod's to hear of in the log, not a reason for the game to drop a player or stop. */
    private void guard(String what, Runnable crossing) {
        try {
            crossing.run();
        } catch (RuntimeException e) {
            LOGGER.error("Mycelium lifecycle: {} failed and changed nothing", what, e);
        }
    }
}
