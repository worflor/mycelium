package mycelium.bridges.lifecycle.conformance;

import mycelium.bridges.lifecycle.PlayerLifecycle;
import mycelium.item_component.v1.model.TagNode;
import mycelium.state_lifecycle.v1.model.HostRef;
import mycelium.state_lifecycle.v1.model.LifecyclePort;
import mycelium.state_lifecycle.v1.model.SlotId;
import mycelium.state_lifecycle.v1.model.SlotRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The conformance cases that need the bridge's own source (decision 0016): the entry point that builds the bridge's own port
 * ({@link #cases()}, what a mod that takes the bridge registers) and the cases of its internals. Everything else is
 * {@link LifecycleCases}, written against the port, which a mod that overrides the bridge imports and calls with its own
 * {@link LifecycleCases.Subject}; this class is not imported for it, since it mentions what the override replaces.
 *
 * <p>The bridge under test is a lifecycle of its own, made over the cases' registry and installed for the length of a case: the loader's
 * listeners are registered once for the JVM and every installed lifecycle hears them, so the mod's own lifecycle and the one under
 * test see the same mock players, and the mod's is not disturbed.
 */
public final class LifecycleConformance extends LifecycleCases {
    private static final String DATA = "mycelium_conformance_lifecycle";

    /** The bridge's own port, made as a mod makes it and wired to the loader's events. */
    public static final Subject BRIDGE = (server, registry) -> {
        PlayerLifecycle lifecycle = PlayerLifecycle.of(registry, DATA).install();
        lifecycle.attach(server);
        return new Open() {
            @Override
            public LifecyclePort<ServerPlayer, MinecraftServer> port() {
                return lifecycle;
            }

            @Override
            public void stop() {
                lifecycle.stop();
            }

            @Override
            public Optional<LifecyclePort<ServerPlayer, MinecraftServer>> reloaded() {
                return Optional.of(lifecycle.reloaded());
            }

            @Override
            public void close() {
                lifecycle.close();
            }
        };
    };

    private LifecycleConformance() {
    }

    /** The cases, for the bridge's own port, with the cases of its own seams. */
    public static List<Case> cases() {
        List<Case> cases = new ArrayList<>(cases(BRIDGE));
        cases.addAll(extraCases());
        return cases;
    }

    // ---------------------------------------------------------------------------------------------------- this bridge's own cases

    private static List<Case> extraCases() {
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("a_bad_stored_form_is_set_apart", level -> badForm((ServerLevel) level)));
        cases.add(new Case("a_form_of_a_slot_nobody_declares_survives", level -> unknownSlot((ServerLevel) level)));
        cases.add(new Case("installing_twice_is_one_set_of_listeners", level -> installTwice((ServerLevel) level)));
        cases.add(new Case("the_game_writes_the_saved_data", level -> gameWrites((ServerLevel) level)));
        return cases;
    }

    /** A stored form that does not decode costs that value and never the player: it is kept apart and the player joins without it. */
    private static void badForm(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PlayerLifecycle lifecycle = open(server);
        List<ServerPlayer> made = new ArrayList<>();
        try {
            UUID id = UUID.randomUUID();
            ServerPlayer first = Wire.join(level, id, "bad");
            made.add(first);
            HostRef ref = lifecycle.current(first).orElseThrow(() -> new AssertionError("no ref after the join"));
            lifecycle.write(CHARM, ref, "good");
            Wire.leave(first);
            lifecycle.players().putStored(ref.host(), CHARM.id(), wrongForm());
            int before = lifecycle.quarantinedForms();
            ServerPlayer again = Wire.join(level, id, "bad");
            made.add(again);
            HostRef second = lifecycle.current(again).orElse(null);
            expect("a player whose stored value does not decode still joins", second != null);
            expect("without that value: " + lifecycle.players().peek(CHARM, second), lifecycle.players().peek(CHARM, second).isAbsent());
            expect("the bad form is set apart, not lost: " + before + " then " + lifecycle.quarantinedForms(), lifecycle.quarantinedForms() == before + 1);
            expect("and is no longer the stored form", lifecycle.players().stored(ref.host(), CHARM.id()).isEmpty());
        } finally {
            made.stream().filter(Wire::online).forEach(Wire::leave);
            lifecycle.close();
        }
    }

    /** What the world holds for a slot the mod no longer declares is carried through a save and a reload, untouched. */
    private static void unknownSlot(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PlayerLifecycle lifecycle = open(server);
        List<ServerPlayer> made = new ArrayList<>();
        try {
            ServerPlayer player = Wire.join(level, UUID.randomUUID(), "unknown");
            made.add(player);
            HostRef ref = lifecycle.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
            SlotId gone = new SlotId(NAMESPACE + ":a_slot_the_mod_dropped");
            TagNode form = TagNode.TagString.of("left behind");
            lifecycle.players().putStored(ref.host(), gone, form);
            LifecyclePort<ServerPlayer, MinecraftServer> reloaded = lifecycle.reloaded();
            expect("a form for a slot nobody declares is still there after a save and a reload: " + reloaded.players().stored(ref.host(), gone),
                    reloaded.players().stored(ref.host(), gone).equals(Optional.of(form)));
        } finally {
            made.stream().filter(Wire::online).forEach(Wire::leave);
            lifecycle.close();
        }
    }

    /** The loader's listeners are registered once; installing a lifecycle again does not make it hear a login twice. */
    private static void installTwice(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PlayerLifecycle lifecycle = open(server);
        lifecycle.install();
        lifecycle.install();
        List<ServerPlayer> made = new ArrayList<>();
        try {
            ServerPlayer player = Wire.join(level, UUID.randomUUID(), "twice");
            made.add(player);
            HostRef ref = lifecycle.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
            expect("one login is one incarnation: " + ref, ref.incarnation().value() == 1);
        } finally {
            made.stream().filter(Wire::online).forEach(Wire::leave);
            lifecycle.close();
        }
    }

    /** The game itself writes the saved data when the world saves: a durable value live at the save is in a file afterwards. */
    private static void gameWrites(ServerLevel level) {
        MinecraftServer server = level.getServer();
        PlayerLifecycle lifecycle = open(server);
        List<ServerPlayer> made = new ArrayList<>();
        try {
            ServerPlayer player = Wire.join(level, UUID.randomUUID(), "saves");
            made.add(player);
            HostRef ref = lifecycle.current(player).orElseThrow(() -> new AssertionError("no ref after the join"));
            lifecycle.write(CHARM, ref, "in the file");
            Path world = server.getWorldPath(LevelResource.ROOT); // the overworld's data folder is the world's (1.21) or the dimension's (26)
            server.saveEverything(true, true, true);
            boolean written = false;
            for (int i = 0; i < 40 && !written; i++) {
                written = fileNamed(world, DATA + ".dat");
                if (!written) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            expect("after the world saved, the world folder holds " + DATA + ".dat (" + world + ")", written);
        } finally {
            made.stream().filter(Wire::online).forEach(Wire::leave);
            lifecycle.close();
        }
    }

    private static boolean fileNamed(Path dir, String name) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir, 8)) {
            return walk.anyMatch(p -> p.getFileName().toString().equals(name));
        } catch (IOException e) {
            return false;
        }
    }

    private static PlayerLifecycle open(MinecraftServer server) {
        SlotRegistry registry = registry();
        PlayerLifecycle lifecycle = PlayerLifecycle.of(registry, DATA).install();
        lifecycle.attach(server);
        return lifecycle;
    }
}
