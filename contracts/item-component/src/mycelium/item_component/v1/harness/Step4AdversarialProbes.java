package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing Step 4 requirements:
 * Owned native projections, bitfield masks, and tri-state reconciliation restoreEdited(S0, N0, N1).
 */
public final class Step4AdversarialProbes {

    public static void runAll(RosettaTranslator translator) {
        System.out.println("Running Step 4 Adversarial Probes...");
        int failures = 0;

        try { testSubCompoundFieldProjectionPreservesSiblings(); System.out.println("  [PASS] testSubCompoundFieldProjectionPreservesSiblings"); }
        catch (Throwable t) { System.out.println("  [FAIL] testSubCompoundFieldProjectionPreservesSiblings: " + t.getMessage()); failures++; }

        try { testMalformedDisplayTagNotDeletedByEmptyProjection(); System.out.println("  [PASS] testMalformedDisplayTagNotDeletedByEmptyProjection"); }
        catch (Throwable t) { System.out.println("  [FAIL] testMalformedDisplayTagNotDeletedByEmptyProjection: " + t.getMessage()); failures++; }

        try { testResetProjectionErasesNamePreservesSiblings(); System.out.println("  [PASS] testResetProjectionErasesNamePreservesSiblings"); }
        catch (Throwable t) { System.out.println("  [FAIL] testResetProjectionErasesNamePreservesSiblings: " + t.getMessage()); failures++; }

        try { testBitmaskProjectionCommutes(); System.out.println("  [PASS] testBitmaskProjectionCommutes"); }
        catch (Throwable t) { System.out.println("  [FAIL] testBitmaskProjectionCommutes: " + t.getMessage()); failures++; }

        try { testTriStateAnvilReconciliation(translator); System.out.println("  [PASS] testTriStateAnvilReconciliation"); }
        catch (Throwable t) { System.out.println("  [FAIL] testTriStateAnvilReconciliation: " + t.getMessage()); failures++; }

        try { testMalformedNativeNameNotTreatedAsRemoval(translator); System.out.println("  [PASS] testMalformedNativeNameNotTreatedAsRemoval"); }
        catch (Throwable t) { System.out.println("  [FAIL] testMalformedNativeNameNotTreatedAsRemoval: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 4 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 4 Adversarial Probes: ALL PASSED");
    }

    public static void runAll() {
        runAll(RosettaTranslator.standard());
    }

    public static void testSubCompoundFieldProjectionPreservesSiblings() {
        TagNode.TagList lore = TagNode.TagList.of(TagNode.TagString.of("Ancient blade forged in starlight"));
        TagNode.TagCompound display = TagNode.TagCompound.empty()
                .with("Lore", lore)
                .with("color", TagNode.TagInt.of(0xFF00FF));
        TagNode.TagCompound nativeRoot = TagNode.TagCompound.empty().with("display", display);

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

    public static void testMalformedDisplayTagNotDeletedByEmptyProjection() {
        TagNode.TagCompound nativeRoot = TagNode.TagCompound.empty()
                .with("display", TagNode.TagInt.of(42)); // Malformed scalar display

        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        CarrierSnapshot emptySemantic = CarrierSnapshot.empty();

        TagNode.TagCompound projected = nameRecipe.project(emptySemantic, nativeRoot);
        if (!projected.has("display") || !Objects.equals(projected.get("display"), TagNode.TagInt.of(42))) {
            throw new AssertionError("Empty projection deleted malformed native display tag!");
        }
    }

    public static void testResetProjectionErasesNamePreservesSiblings() {
        TagNode.TagCompound display = TagNode.TagCompound.empty()
                .with("Name", TagNode.TagString.of("Previous Custom Name"))
                .with("color", TagNode.TagInt.of(0xFF0000));
        TagNode.TagCompound nativeRoot = TagNode.TagCompound.empty().with("display", display);

        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        CarrierSnapshot resetSemantic = CarrierSnapshot.empty(); // Inherit / reset

        TagNode.TagCompound projected = nameRecipe.project(resetSemantic, nativeRoot);
        TagNode.TagCompound updatedDisplay = projected.getCompound("display").orElseThrow();

        if (updatedDisplay.has("Name")) {
            throw new AssertionError("Reset to Inherit failed to erase display.Name!");
        }
        if (!updatedDisplay.has("color") || updatedDisplay.getInt("color").orElse(0) != 0xFF0000) {
            throw new AssertionError("Sibling field color was lost during Name reset!");
        }
    }

    public static void testBitmaskProjectionCommutes() {
        int initialFlags = 0b00000001; // Bit 0 owned by foreign system
        int ownedMask = 0b00000010;    // Bit 1 owned by HideFlags recipe

        int setFlags = NativeProjectionRecipe.applyBitmask(initialFlags, ownedMask, 0b00000010);
        if (setFlags != 0b00000011) {
            throw new AssertionError("Expected 0b11, got " + Integer.toBinaryString(setFlags));
        }

        int clearedFlags = NativeProjectionRecipe.applyBitmask(setFlags, ownedMask, 0);
        if (clearedFlags != 0b00000001) {
            throw new AssertionError("Expected 0b01, got " + Integer.toBinaryString(clearedFlags));
        }
    }

    public static void testTriStateAnvilReconciliation(RosettaTranslator translator) {
        ComponentId nameId = ComponentId.of("minecraft:custom_name");
        ComponentId secretId = ComponentId.of("future:secret");

        ComponentRegistry reg = ComponentRegistry.builder()
                .register(nameId, ComponentCodec.STRING, "")
                .register(secretId, ComponentCodec.STRING, "")
                .build();

        CarrierSnapshot s0 = CarrierSnapshot.empty()
                .with(nameId, "Original")
                .with(secretId, "retained_future_token");

        TagNode.TagCompound n0 = translator.toLegacyNbt(s0, reg);
        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        TagNode.TagCompound n0Projected = nameRecipe.project(s0, n0);

        TagNode.TagCompound n1 = n0Projected.with("display", TagNode.TagCompound.empty().with("Name", TagNode.TagString.of("Renamed By Anvil")));

        CarrierSnapshot s1 = nameRecipe.restoreEdited(s0, n0Projected, n1, reg);

        if (!s1.getString(nameId).map(s -> s.equals("Renamed By Anvil")).orElse(false)) {
            throw new AssertionError("Reconciliation failed to observe native anvil rename! Got: " + s1.get(nameId));
        }
        if (!s1.getString(secretId).map(s -> s.equals("retained_future_token")).orElse(false)) {
            throw new AssertionError("Reconciliation destroyed retained modern component! Got: " + s1.get(secretId));
        }
    }

    public static void testMalformedNativeNameNotTreatedAsRemoval(RosettaTranslator translator) {
        ComponentId nameId = ComponentId.of("minecraft:custom_name");
        ComponentRegistry reg = ComponentRegistry.builder().register(nameId, ComponentCodec.STRING, "").build();

        CarrierSnapshot s0 = CarrierSnapshot.empty().with(nameId, "Original Name");
        TagNode.TagCompound n0 = translator.toLegacyNbt(s0, reg);
        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();
        TagNode.TagCompound n0Projected = nameRecipe.project(s0, n0);

        // n1 has malformed non-string Name (TagInt 42)
        TagNode.TagCompound n1 = n0Projected.with("display", TagNode.TagCompound.empty().with("Name", TagNode.TagInt.of(42)));

        CarrierSnapshot s1 = nameRecipe.restoreEdited(s0, n0Projected, n1, reg);

        if (s1.isRemoved(nameId)) {
            throw new AssertionError("Malformed native Name was erroneously treated as a valid semantic removal!");
        }
        if (!s1.getString(nameId).map(s -> s.equals("Original Name")).orElse(false)) {
            throw new AssertionError("Original semantic name was lost when encountering malformed native Name!");
        }
        if (s1.recoveryRecords().isEmpty()) {
            throw new AssertionError("Malformed native Name was not recorded as evidence in recovery records!");
        }
    }
}
