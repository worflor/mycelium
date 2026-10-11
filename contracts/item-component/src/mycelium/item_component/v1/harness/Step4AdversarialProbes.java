package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing Step 4 requirements:
 * Owned native projections, bitfield masks, and tri-state reconciliation restoreEdited(S0, N0, N1).
 */
public final class Step4AdversarialProbes {

    public static void runAll() {
        System.out.println("Running Step 4 Adversarial Probes...");
        int failures = 0;

        try { testSubCompoundFieldProjectionPreservesSiblings(); System.out.println("  [PASS] testSubCompoundFieldProjectionPreservesSiblings"); }
        catch (Throwable t) { System.out.println("  [FAIL] testSubCompoundFieldProjectionPreservesSiblings: " + t.getMessage()); failures++; }

        try { testBitmaskProjectionCommutes(); System.out.println("  [PASS] testBitmaskProjectionCommutes"); }
        catch (Throwable t) { System.out.println("  [FAIL] testBitmaskProjectionCommutes: " + t.getMessage()); failures++; }

        try { testTriStateAnvilReconciliation(); System.out.println("  [PASS] testTriStateAnvilReconciliation"); }
        catch (Throwable t) { System.out.println("  [FAIL] testTriStateAnvilReconciliation: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 4 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 4 Adversarial Probes: ALL PASSED");
    }

    /**
     * Requirement 4.A:
     * Custom name recipe owns display.Name, NOT the entire display compound.
     * Sibling fields like display.Lore or display.color must not be destroyed when setting display.Name.
     */
    public static void testSubCompoundFieldProjectionPreservesSiblings() {
        // Native root with preexisting display compound containing Lore
        TagNode.TagList lore = TagNode.TagList.of(TagNode.TagString.of("Ancient blade forged in starlight"));
        TagNode.TagCompound display = TagNode.TagCompound.empty()
                .with("Lore", lore)
                .with("color", TagNode.TagInt.of(0xFF00FF));
        TagNode.TagCompound nativeRoot = TagNode.TagCompound.empty().with("display", display);

        // Project modern custom_name: "Starlight Edge"
        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        CarrierSnapshot semantic = CarrierSnapshot.empty()
                .with(ComponentId.of("minecraft:custom_name"), "Starlight Edge");

        TagNode.TagCompound projected = nameRecipe.project(semantic, nativeRoot);

        TagNode.TagCompound updatedDisplay = projected.getCompound("display").orElseThrow();
        if (!updatedDisplay.getString("Name").map(s -> s.equals("Starlight Edge")).orElse(false)) {
            throw new AssertionError("display.Name was not projected correctly!");
        }
        if (!updatedDisplay.has("Lore") || !updatedDisplay.has("color")) {
            throw new AssertionError("display.Lore or display.color was clobbered by custom name projection!");
        }
    }

    /**
     * Requirement 4.A:
     * Bitfields: newFlags = (oldFlags & ~ownedMask) | requestedBits.
     * Setting/clearing owned bits in HideFlags must commute and preserve unowned bits.
     */
    public static void testBitmaskProjectionCommutes() {
        int initialFlags = 0b00000001; // Bit 0 owned by foreign system
        int ownedMask = 0b00000010;    // Bit 1 owned by HideFlags recipe

        // Set bit 1
        int setFlags = NativeProjectionRecipe.applyBitmask(initialFlags, ownedMask, 0b00000010);
        if (setFlags != 0b00000011) {
            throw new AssertionError("Expected 0b11, got " + Integer.toBinaryString(setFlags));
        }

        // Clear bit 1
        int clearedFlags = NativeProjectionRecipe.applyBitmask(setFlags, ownedMask, 0);
        if (clearedFlags != 0b00000001) {
            throw new AssertionError("Expected 0b01, got " + Integer.toBinaryString(clearedFlags));
        }
    }

    /**
     * Requirement 4.B:
     * Tri-state reconciliation: restoreEdited(S0, N0, N1).
     * S0: { custom_name: "Original", modern_secret: "retained_future_token" }
     * N0: emitted native state with display.Name = "Original"
     * N1: native anvil renamed display.Name = "Renamed By Anvil"
     * Reconcile: S1 must have custom_name = "Renamed By Anvil" AND keep modern_secret!
     */
    public static void testTriStateAnvilReconciliation() {
        ComponentId nameId = ComponentId.of("minecraft:custom_name");
        ComponentId secretId = ComponentId.of("future:secret");

        ComponentRegistry reg = ComponentRegistry.builder()
                .register(nameId, ComponentCodec.STRING, "")
                .register(secretId, ComponentCodec.STRING, "")
                .build();

        // S0: Initial semantic state
        CarrierSnapshot s0 = CarrierSnapshot.empty()
                .with(nameId, "Original")
                .with(secretId, "retained_future_token");

        // N0: Baseline emitted by Rosetta
        RosettaTranslator translator = RosettaTranslator.standard();
        TagNode.TagCompound n0 = translator.toLegacyNbt(s0, reg);

        // N1: Native anvil modification (modifies display.Name directly in legacy tag)
        TagNode.TagCompound n1 = n0.with("display", TagNode.TagCompound.empty().with("Name", TagNode.TagString.of("Renamed By Anvil")));

        // Reconcile: restoreEdited(s0, n0, n1)
        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        CarrierSnapshot s1 = nameRecipe.restoreEdited(s0, n0, n1, reg);

        if (!s1.getString(nameId).map(s -> s.equals("Renamed By Anvil")).orElse(false)) {
            throw new AssertionError("Reconciliation failed to observe native anvil rename! Got: " + s1.get(nameId));
        }
        if (!s1.getString(secretId).map(s -> s.equals("retained_future_token")).orElse(false)) {
            throw new AssertionError("Reconciliation destroyed retained modern component! Got: " + s1.get(secretId));
        }
    }
}
