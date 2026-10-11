package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 2: Prototype-aware patches & canonical reads).
 */
public final class Step2AdversarialProbes {

    public static void runAll() {
        System.out.println("Running Step 2 Adversarial Probes...");
        int failures = 0;

        try { testRemovedComponentTypedReadAbsence(); System.out.println("  [PASS] testRemovedComponentTypedReadAbsence"); }
        catch (Throwable t) { System.out.println("  [FAIL] testRemovedComponentTypedReadAbsence: " + t.getMessage()); failures++; }

        try { testPatchAlgebraInheritSetRemove(); System.out.println("  [PASS] testPatchAlgebraInheritSetRemove"); }
        catch (Throwable t) { System.out.println("  [FAIL] testPatchAlgebraInheritSetRemove: " + t.getMessage()); failures++; }

        try { testEffectiveReadWithPrototype(); System.out.println("  [PASS] testEffectiveReadWithPrototype"); }
        catch (Throwable t) { System.out.println("  [FAIL] testEffectiveReadWithPrototype: " + t.getMessage()); failures++; }

        try { testPrototypeCanonicalizationLaw(); System.out.println("  [PASS] testPrototypeCanonicalizationLaw"); }
        catch (Throwable t) { System.out.println("  [FAIL] testPrototypeCanonicalizationLaw: " + t.getMessage()); failures++; }

        try { testDecoderAliasCollision(); System.out.println("  [PASS] testDecoderAliasCollision"); }
        catch (Throwable t) { System.out.println("  [FAIL] testDecoderAliasCollision: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 2 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 2 Adversarial Probes: ALL PASSED");
    }

    /**
     * Audit Observation B1:
     * snapshot.withRemoval(def.id()); snapshot.get(def) currently ignores removal and returns 99.
     * Explicit removal must resolve to absence (null), not secretly restore default.
     */
    public static void testRemovedComponentTypedReadAbsence() {
        ComponentId id = ComponentId.of("test:val");
        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, ComponentCodec.INT, 99);
        CarrierSnapshot snapshot = CarrierSnapshot.empty().withRemoval(id);

        if (!snapshot.isRemoved(id)) {
            throw new AssertionError("Snapshot should report isRemoved(id) == true");
        }
        Integer readVal = snapshot.get(def);
        if (readVal != null) {
            throw new AssertionError("REMOVAL LEAK: snapshot.get(def) returned " + readVal + " instead of null (absence)!");
        }
        Optional<Integer> opt = snapshot.getOptional(def);
        if (opt.isPresent()) {
            throw new AssertionError("REMOVAL LEAK: snapshot.getOptional(def) was present!");
        }
    }

    /**
     * Audit Requirement B2:
     * readPatch(id) -> Inherit | Set(value) | Remove
     */
    public static void testPatchAlgebraInheritSetRemove() {
        ComponentId id = ComponentId.of("test:patch");
        CarrierSnapshot empty = CarrierSnapshot.empty();
        ComponentPatch<Integer> p1 = empty.readPatch(id);
        if (!(p1 instanceof ComponentPatch.Inherit)) {
            throw new AssertionError("Expected Inherit for untouched key, got " + p1);
        }

        CarrierSnapshot setSnap = empty.with(id, 42);
        ComponentPatch<Integer> p2 = setSnap.readPatch(id);
        if (!(p2 instanceof ComponentPatch.Set<Integer> setPatch) || !Objects.equals(setPatch.value(), 42)) {
            throw new AssertionError("Expected Set(42), got " + p2);
        }

        CarrierSnapshot remSnap = empty.withRemoval(id);
        ComponentPatch<Integer> p3 = remSnap.readPatch(id);
        if (!(p3 instanceof ComponentPatch.Remove)) {
            throw new AssertionError("Expected Remove, got " + p3);
        }
    }

    /**
     * Audit Requirement B3:
     * readEffective(id, prototype) -> Present(v) | Absent
     * readEffectiveOrElse(..., fallback)
     */
    public static void testEffectiveReadWithPrototype() {
        ComponentId id = ComponentId.of("test:durability");
        ItemPrototype swordProto = ItemPrototype.builder()
                .withBaseComponent(id, 100)
                .build();

        // 1. Inherit case: effective read sees base prototype
        CarrierSnapshot untouched = CarrierSnapshot.empty();
        Optional<Integer> eff1 = untouched.readEffective(id, swordProto);
        if (!eff1.isPresent() || eff1.get() != 100) {
            throw new AssertionError("Expected prototype base value 100, got " + eff1);
        }

        // 2. Override case: effective read sees override
        CarrierSnapshot damaged = untouched.with(id, 75);
        Optional<Integer> eff2 = damaged.readEffective(id, swordProto);
        if (!eff2.isPresent() || eff2.get() != 75) {
            throw new AssertionError("Expected override value 75, got " + eff2);
        }

        // 3. Removal case: effective read sees ABSENCE despite prototype having base value
        CarrierSnapshot removed = untouched.withRemoval(id);
        Optional<Integer> eff3 = removed.readEffective(id, swordProto);
        if (eff3.isPresent()) {
            throw new AssertionError("Removal must yield Absent even if prototype has base value! Got " + eff3);
        }
        Integer fallback = removed.readEffectiveOrElse(id, swordProto, -1);
        if (fallback != -1) {
            throw new AssertionError("Expected fallback -1, got " + fallback);
        }
    }

    /**
     * Audit Requirement B4:
     * decode_P(encode_P(S)) = canonicalize_P(S)
     * Setting damage back to prototype default eliminates the override in canonicalization.
     */
    public static void testPrototypeCanonicalizationLaw() {
        ComponentId id = ComponentId.of("test:durability");
        ItemPrototype swordProto = ItemPrototype.builder()
                .withBaseComponent(id, 100)
                .build();
        ComponentRegistry reg = ComponentRegistry.builder()
                .register(id, ComponentCodec.INT, 100)
                .build();

        // An override identical to prototype base value canonicalizes away
        CarrierSnapshot explicitDefault = CarrierSnapshot.empty().with(id, 100);
        CarrierSnapshot canonical = explicitDefault.canonicalize(swordProto, reg);
        if (canonical.has(id)) {
            throw new AssertionError("Canonicalization must elide override matching prototype base value!");
        }
    }

    /**
     * Audit Observation D3:
     * "damage" vs "minecraft:damage" alias collision must not silently choose last iterated alias.
     */
    public static void testDecoderAliasCollision() {
        ComponentId id = ComponentId.of("minecraft:damage");
        ComponentRegistry reg = ComponentRegistry.builder()
                .register(id, ComponentCodec.INT, 0)
                .build();

        // Two entries in same fenced compound normalizing to same canonical ID
        Map<String, TagNode> tags = new LinkedHashMap<>();
        tags.put("damage", TagNode.TagInt.of(5));
        tags.put("minecraft:damage", TagNode.TagInt.of(10));

        TagNode.TagCompound nbt = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, new TagNode.TagCompound(tags));

        RosettaTranslator translator = RosettaTranslator.standard();
        CarrierSnapshot decoded = translator.fromLegacyNbt(nbt, reg);

        // A deterministic conflict must not silently overwrite one with the other as a valid component
        if (decoded.has(id)) {
            throw new AssertionError("Alias collision between 'damage' and 'minecraft:damage' must be flagged as conflict/quarantined, not silently resolved!");
        }
        if (!decoded.unmappedTags().containsKey("damage") || !decoded.unmappedTags().containsKey("minecraft:damage")) {
            throw new AssertionError("Both colliding tags must be preserved in unmapped/quarantine! Got: " + decoded.unmappedTags());
        }
    }
}
