package mycelium.bridges.item_carrier;

import mycelium.item_component.v1.model.CarrierSnapshot;
import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentRead;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.FencedCarrier;
import mycelium.item_component.v1.model.LegacyKeyMigration;
import mycelium.item_component.v1.model.OnQuarantine;
import mycelium.item_component.v1.model.StackCarrier;
import mycelium.item_component.v1.model.TagNode;
import mycelium.item_component.v1.model.WriteOutcome;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * Mycelium's {@code item-carrier} bridge, variant {@code custom-data} (Minecraft 1.20.5 on): {@link StackCarrier} for
 * {@link ItemStack}, with the stack's state in its {@code minecraft:custom_data} component. It supplies what a mod used to
 * hand-write around {@link FencedCarrier}: the compound in and out of the stack, and Mojang's tags to and from
 * {@link TagNode} ({@link Nbt}). Everything else (the fence, default elision, quarantine, old keys) is the pure carrier's,
 * so it is the same on every version.
 *
 * <p>A stack with nothing left to carry has no {@code custom_data} at all, so it is again identical to a plain one and stacks
 * with it. Another mod's keys in the same compound are kept by every write.
 *
 * <p>This class is open on purpose (decision 0016: offer, never police). Extend it and override the seams
 * ({@link #root(ItemStack)}, {@link #store(ItemStack, TagNode.TagCompound)}) to keep state somewhere else, or write your own
 * {@link StackCarrier} and name it with {@code bridges { override("item-carrier", "...") }}.
 */
public class ItemCarrier implements StackCarrier<ItemStack> {
    private final FencedCarrier carrier;

    /** A bridge over {@code carrier}, which fixes the registry and the old-key migration in force. */
    protected ItemCarrier(FencedCarrier carrier) {
        this.carrier = java.util.Objects.requireNonNull(carrier, "carrier");
    }

    public static ItemCarrier of(ComponentRegistry registry) {
        return over(FencedCarrier.standard(registry));
    }

    public static ItemCarrier of(ComponentRegistry registry, LegacyKeyMigration migration) {
        return over(FencedCarrier.standard(registry, migration));
    }

    public static ItemCarrier over(FencedCarrier carrier) {
        return new ItemCarrier(carrier);
    }

    @Override
    public FencedCarrier carrier() {
        return carrier;
    }

    // ---------------------------------------------------------------------------------------------------- the seams

    /** Whether the stack carries nothing and can carry nothing: no stack, or the empty one. */
    protected boolean isEmpty(ItemStack stack) {
        return stack == null || stack.isEmpty();
    }

    /** The stack's root compound, in: its {@code custom_data}, or an empty compound when it has none. */
    protected TagNode.TagCompound root(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? TagNode.TagCompound.empty() : Nbt.compound(data.copyTag());
    }

    /** The stack's root compound, out: an empty one is no {@code custom_data} at all, so the stack is again a plain one's. */
    protected void store(ItemStack stack, TagNode.TagCompound root) {
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(Nbt.compoundTag(root)));
        }
    }

    // ---------------------------------------------------------------------------------------------------- the port

    @Override
    public CarrierSnapshot read(ItemStack stack) {
        return isEmpty(stack) ? CarrierSnapshot.empty() : carrier.read(root(stack));
    }

    @Override
    public <T> ComponentRead<T> read(ItemStack stack, ComponentDefinition<T> def) {
        return isEmpty(stack) ? new ComponentRead.Absent<>() : carrier.read(root(stack), def);
    }

    @Override
    public WriteOutcome write(ItemStack stack, CarrierSnapshot snapshot, OnQuarantine mode) {
        return isEmpty(stack) ? unchanged() : apply(stack, carrier.write(root(stack), snapshot, mode));
    }

    @Override
    public <T> WriteOutcome set(ItemStack stack, ComponentDefinition<T> def, T value, OnQuarantine mode) {
        return isEmpty(stack) ? unchanged() : apply(stack, carrier.set(root(stack), def, value, mode));
    }

    @Override
    public WriteOutcome clear(ItemStack stack, ComponentDefinition<?> def, OnQuarantine mode) {
        return isEmpty(stack) ? unchanged() : apply(stack, carrier.clear(root(stack), def, mode));
    }

    private WriteOutcome apply(ItemStack stack, WriteOutcome outcome) {
        if (outcome instanceof WriteOutcome.Written written && written.changed()) {
            store(stack, written.root());
        }
        return outcome;
    }

    private static WriteOutcome unchanged() {
        return new WriteOutcome.Written(TagNode.TagCompound.empty(), false);
    }

    // ---------------------------------------------------------------------------------------------------- raw roots

    /**
     * The stack root a snapshot is, as raw NBT: the fenced compound or nothing. For the places that build an item's
     * {@code custom_data} outside a stack (a display entity's item written as data, a loot entry's component map).
     */
    public CompoundTag rawRoot(CarrierSnapshot snapshot) {
        WriteOutcome out = carrier.write(TagNode.TagCompound.empty(), snapshot, OnQuarantine.KEEP);
        return Nbt.compoundTag(out.root());
    }
}
