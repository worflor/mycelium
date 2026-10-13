package mycelium.bridges.item_presentation;

import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.StackCarrier;
import mycelium.item_presentation.v1.model.CarrierView;
import mycelium.item_presentation.v1.model.ComponentCatalog;
import mycelium.item_presentation.v1.model.Layout;
import mycelium.item_presentation.v1.model.Plan;
import mycelium.item_presentation.v1.model.ReferencePlan;
import mycelium.item_presentation.v1.model.Result;
import mycelium.item_presentation.v1.model.Selection;
import mycelium.item_presentation.v1.model.Selector;
import mycelium.item_presentation.v1.model.StackPresenter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Mycelium's {@code item-presentation} bridge, variant {@code model-overrides} (Minecraft 1.14 to 1.21.3): {@link StackPresenter}
 * for {@link ItemStack}. The item model the build generated carries overrides on the {@code custom_model_data} predicate, a
 * number, so the selector this writes is the selection's locked code as that number: the stack's root tag
 * {@code CustomModelData} before 1.20.5, the component of that name from it (an overlay).
 *
 * <p>What a mod used to hand-write per version: the number's home (a root tag, then a component), its removal when the stack
 * has no selector left, and the same again for the raw item data a display entity is built from ({@link #writeRaw}). The
 * decision of <em>which</em> code a stack carries is the plan's (the program, compiled, with the codes the mod's lock keeps),
 * never arithmetic in this class or the mod, so the stack, the generated model and the program cannot disagree.
 *
 * <p>This class is open on purpose (decision 0016: offer, never police). Extend it and override a seam, or write your own
 * {@link StackPresenter} and name it with {@code bridges { override("item-presentation", "...") }}.
 */
public class ItemPresenter implements StackPresenter<ItemStack> {
    private final Plan plan;
    private final ComponentRegistry registry;
    private final ComponentCatalog catalog;
    private final StackCarrier<ItemStack> carrier;

    /**
     * A presenter for {@code plan} that reads a stack's state through {@code carrier} (the {@code item-carrier} bridge's, or a
     * mod's own over the same {@code registry}) and the {@code catalog} its programs were checked against.
     *
     * @throws IllegalStateException when the plan was compiled for another era than this Minecraft version draws by, which would
     *                               write a selector the generated assets do not read
     */
    public ItemPresenter(Plan plan, ComponentRegistry registry, ComponentCatalog catalog, StackCarrier<ItemStack> carrier) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.carrier = Objects.requireNonNull(carrier, "carrier");
        if (plan.realization() != Cmd.REALIZATION) {
            throw new IllegalStateException("Mycelium: the presentation plan for " + plan.item() + " was compiled for "
                    + plan.realization().id() + ", and this Minecraft version draws by " + Cmd.REALIZATION.id()
                    + ": it was built for another target");
        }
    }

    public static ItemPresenter of(Plan plan, ComponentRegistry registry, ComponentCatalog catalog, StackCarrier<ItemStack> carrier) {
        return new ItemPresenter(plan, registry, catalog, carrier);
    }

    /**
     * The presenter of {@code item} (an item id such as {@code example:status_tool}), from the plan and the catalog the
     * build generated into the mod's resources ({@code myceliumPresentation}), read through {@code loader}.
     *
     * @throws IllegalStateException when either resource is missing or does not read
     */
    public static ItemPresenter load(ClassLoader loader, String item, ComponentRegistry registry, StackCarrier<ItemStack> carrier) {
        Result<ComponentCatalog> catalog = ComponentCatalog.parse(resource(loader, Layout.catalog()));
        if (catalog instanceof Result.Failed<ComponentCatalog> failed) {
            throw new IllegalStateException("Mycelium: the presentation catalog does not read: " + failed.diagnostics());
        }
        Result<Plan> plan = ReferencePlan.parse(resource(loader, Layout.plan(item)));
        if (plan instanceof Result.Failed<Plan> failed) {
            throw new IllegalStateException("Mycelium: the presentation plan for " + item + " does not read: " + failed.diagnostics());
        }
        return new ItemPresenter(((Result.Ok<Plan>) plan).value(), registry, ((Result.Ok<ComponentCatalog>) catalog).value(), carrier);
    }

    private static String resource(ClassLoader loader, String path) {
        try (InputStream in = loader.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Mycelium: the mod has no resource " + path + " (is the myceliumPresentation task wired, and the item's program written?)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Mycelium: " + path + " could not be read", e);
        }
    }

    // ---------------------------------------------------------------------------------------------------- the port

    @Override
    public Plan plan() {
        return plan;
    }

    @Override
    public Selection select(CarrierSnapshot snapshot) {
        return plan.select(new CarrierView(snapshot, registry, catalog));
    }

    @Override
    public Selection select(ItemStack stack) {
        return select(stack == null || stack.isEmpty() ? CarrierSnapshot.empty() : carrier.read(stack));
    }

    @Override
    public Selector apply(ItemStack stack, Selection selection) {
        Selector selector = plan.selectorOf(selection);
        if (stack != null && !stack.isEmpty()) {
            Cmd.write(stack, selector);
        }
        return selector;
    }

    @Override
    public Selector read(ItemStack stack) {
        return stack == null || stack.isEmpty() ? new Selector.None() : Cmd.read(stack);
    }

    // ---------------------------------------------------------------------------------------------------- raw item data

    /**
     * Writes {@code selector} into the raw data of an item a mod builds by hand (a display entity's {@code item}), the way this
     * Minecraft version stores it: the base model writes nothing. Everything else in the item data stays, and a selector the
     * data already carried is replaced by a later one but never removed by the base (build the data fresh, as a display entity's is).
     *
     * @param item an item compound: {@code id}, the count, and the stack's components (or its {@code tag} before 1.20.5)
     */
    public void writeRaw(CompoundTag item, Selector selector) {
        Cmd.writeRaw(item, selector);
    }

    /** The selection of {@code snapshot}, written into the raw item data. */
    public Selector writeRaw(CompoundTag item, CarrierSnapshot snapshot) {
        Selector selector = plan.selectorOf(select(snapshot));
        Cmd.writeRaw(item, selector);
        return selector;
    }
}
