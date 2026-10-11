package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 2: Prototype-aware patches & canonical reads).
 */
public final class Step2AdversarialProbes {

    public static void runAll(RosettaTranslator translator) {
        System.out.println("Running Step 2 Adversarial Probes...");
        int failures = 0;

        try { testRemovedComponentTypedReadAbsence(); System.out.println("  [PASS] testRemovedComponentTypedReadAbsence"); }
        catch (Throwable t) { System.out.println("  [FAIL] testRemovedComponentTypedReadAbsence: " + t.getMessage()); failures++; }

        try { testPatchAlgebraInheritSetRemove(); System.out.println("  [PASS] testPatchAlgebraInheritSetRemove"); }
        catch (Throwable t) { System.out.println("  [FAIL] testPatchAlgebraInheritSetRemove: " + t.getMessage()); failures++; }

        try { testEffectiveReadWithPrototype(); System.out.println("  [PASS] testEffectiveReadWithPrototype"); }
        catch (Throwable t) { System.out.println("  [FAIL] testEffectiveReadWithPrototype: " + t.getMessage()); failures++; }

        try { testPrototypeCanonicalizationLaw(translator); System.out.println("  [PASS] testPrototypeCanonicalizationLaw"); }
        catch (Throwable t) { System.out.println("  [FAIL] testPrototypeCanonicalizationLaw: " + t.getMessage()); failures++; }

        try { testCaseAExplicitPresenceRetainedUnderCanonicalization(); System.out.println("  [PASS] testCaseAExplicitPresenceRetainedUnderCanonicalization"); }
        catch (Throwable t) { System.out.println("  [FAIL] testCaseAExplicitPresenceRetainedUnderCanonicalization: " + t.getMessage()); failures++; }

        try { testCaseBSerializationPreservesPrototypeOverride(translator); System.out.println("  [PASS] testCaseBSerializationPreservesPrototypeOverride"); }
        catch (Throwable t) { System.out.println("  [FAIL] testCaseBSerializationPreservesPrototypeOverride: " + t.getMessage()); failures++; }

        try { testDecoderAliasCollision(translator); System.out.println("  [PASS] testDecoderAliasCollision"); }
        catch (Throwable t) { System.out.println("  [FAIL] testDecoderAliasCollision: " + t.getMessage()); failures++; }

        try { testAliasConflictResolutionSupersedesShortAlias(translator); System.out.println("  [PASS] testAliasConflictResolutionSupersedesShortAlias"); }
        catch (Throwable t) { System.out.println("  [FAIL] testAliasConflictResolutionSupersedesShortAlias: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 2 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 2 Adversarial Probes: ALL PASSED");
    }

    public static void runAll() {
        runAll(RosettaTranslator.standard());
    }

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

    public static void testPatchAlgebraInheritSetRemove() {
        ComponentId id = ComponentId.of("test:patch");
        CarrierSnapshot empty = CarrierSnapshot.empty();
        ComponentPatch<Integer> p0 = empty.readPatch(id);
        if (!(p0 instanceof ComponentPatch.Inherit)) {
            throw new AssertionError("Empty snapshot should return Inherit patch!");
        }

        CarrierSnapshot withVal = empty.with(id, 42);
        ComponentPatch<Integer> p1 = withVal.readPatch(id);
        if (!(p1 instanceof ComponentPatch.Set<Integer> s) || s.value() != 42) {
            throw new AssertionError("Snapshot with value should return Set(42) patch!");
        }

        CarrierSnapshot withRem = withVal.withRemoval(id);
        ComponentPatch<Integer> p2 = withRem.readPatch(id);
        if (!(p2 instanceof ComponentPatch.Remove)) {
            throw new AssertionError("Snapshot with removal should return Remove patch!");
        }
    }

    public static void testEffectiveReadWithPrototype() {
        ComponentId id = ComponentId.of("test:max_durability");
        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, ComponentCodec.INT, 100);

        ItemPrototype prototype = ItemPrototype.builder()
                .withBaseComponent(id, 250)
                .build();

        CarrierSnapshot stackDefault = CarrierSnapshot.empty();
        if (stackDefault.readEffectiveOrElse(def, prototype, 0) != 250) {
            throw new AssertionError("Inherited stack should read prototype base durability 250!");
        }

        CarrierSnapshot stackOverride = stackDefault.with(id, 500);
        if (stackOverride.readEffectiveOrElse(def, prototype, 0) != 500) {
            throw new AssertionError("Overridden stack should read stack durability 500!");
        }

        CarrierSnapshot stackRemoved = stackOverride.withRemoval(id);
        if (stackRemoved.readEffective(def, prototype).isPresent()) {
            throw new AssertionError("Removed component must resolve to absence under prototype!");
        }
    }

    public static void testPrototypeCanonicalizationLaw(RosettaTranslator translator) {
        ComponentId damageId = ComponentId.of("minecraft:damage");
        ComponentDefinition<Integer> damageDef = new ComponentDefinition<>(damageId, ComponentCodec.INT, 0);

        ComponentRegistry registry = ComponentRegistry.builder()
                .register(damageDef)
                .build();

        ItemPrototype diamondSword = ItemPrototype.builder()
                .withBaseComponent(damageId, 0)
                .build();

        CarrierSnapshot damaged = CarrierSnapshot.empty().with(damageId, 15);
        CarrierSnapshot repairedToZero = damaged.with(damageId, 0);

        CarrierSnapshot canonical = repairedToZero.canonicalize(diamondSword, registry);

        if (canonical.has(damageId)) {
            throw new AssertionError("CANONICALIZATION FAILED: Setting damage to prototype default did not elide override back to Inherit!");
        }

        TagNode.TagCompound nbt = translator.toLegacyNbt(canonical, registry, diamondSword);
        CarrierSnapshot decoded = translator.fromLegacyNbt(nbt, registry, diamondSword);

        if (!ValueSemantics.deepEquals(canonical, decoded)) {
            throw new AssertionError("Round-trip canonicalization failed law: decode_P(encode_P(S)) = canonicalize_P(S)");
        }
    }

    public static void testCaseAExplicitPresenceRetainedUnderCanonicalization() {
        ComponentId id = ComponentId.of("test:counter");
        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, ComponentCodec.INT, 0);
        ComponentRegistry registry = ComponentRegistry.builder().register(def).build();

        ItemPrototype emptyPrototype = ItemPrototype.empty();
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, 0);

        CarrierSnapshot canonical = snapshot.canonicalize(emptyPrototype, registry);

        if (!canonical.has(id)) {
            throw new AssertionError("Case A Violation: Explicit presence was removed during canonicalization when prototype lacks component!");
        }
        if (!Objects.equals(canonical.get(id), 0)) {
            throw new AssertionError("Case A Violation: Canonical value mismatch!");
        }
    }

    public static void testCaseBSerializationPreservesPrototypeOverride(RosettaTranslator translator) {
        ComponentId id = ComponentId.of("test:stat");
        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, ComponentCodec.INT, 0);
        ComponentRegistry registry = ComponentRegistry.builder().register(def).build();

        ItemPrototype prototype = ItemPrototype.builder().withBaseComponent(id, 100).build();
        CarrierSnapshot stack = CarrierSnapshot.empty().with(id, 0);

        TagNode.TagCompound nbt = translator.toLegacyNbt(stack, registry, prototype);
        CarrierSnapshot restored = translator.fromLegacyNbt(nbt, registry, prototype);

        Optional<Integer> effective = restored.readEffective(def, prototype);
        if (!effective.isPresent() || effective.get() != 0) {
            throw new AssertionError("Case B Violation: Serialization reversed prototype override! Expected effective 0, got " + effective.orElse(null));
        }
    }

    public static void testDecoderAliasCollision(RosettaTranslator translator) {
        TagNode.TagCompound fenced = TagNode.TagCompound.empty()
                .with("damage", TagNode.TagInt.of(10))
                .with("minecraft:damage", TagNode.TagInt.of(20));

        TagNode.TagCompound root = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        ComponentRegistry registry = ComponentRegistry.builder()
                .register(ComponentId.of("minecraft:damage"), ComponentCodec.INT, 0)
                .build();

        CarrierSnapshot decoded = translator.fromLegacyNbt(root, registry);

        if (decoded.has(ComponentId.of("minecraft:damage"))) {
            throw new AssertionError("Decoder picked an arbitrary winner during alias collision!");
        }
        if (!decoded.unmappedTags().containsKey("damage") || !decoded.unmappedTags().containsKey("minecraft:damage")) {
            throw new AssertionError("Colliding candidate aliases were not safely retained in unmapped quarantine tags!");
        }
    }

    public static void testAliasConflictResolutionSupersedesShortAlias(RosettaTranslator translator) {
        TagNode.TagCompound fenced = TagNode.TagCompound.empty()
                .with("damage", TagNode.TagInt.of(10))
                .with("minecraft:damage", TagNode.TagInt.of(20));
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        ComponentId damageId = ComponentId.of("minecraft:damage");
        ComponentRegistry registry = ComponentRegistry.builder().register(damageId, ComponentCodec.INT, 0).build();

        CarrierSnapshot decoded = translator.fromLegacyNbt(root, registry);
        CarrierSnapshot repaired = decoded.with(damageId, 5);

        TagNode.TagCompound reSaved = translator.toLegacyNbt(repaired, registry);
        CarrierSnapshot reloaded = translator.fromLegacyNbt(reSaved, registry);

        if (!reloaded.has(damageId) || !Objects.equals(reloaded.get(damageId), 5)) {
            throw new AssertionError("Repaired value 5 was not preserved on reload!");
        }
        if (reloaded.unmappedTags().containsKey("damage")) {
            throw new AssertionError("Short alias 'damage' resurrected after explicit repair of canonical component!");
        }
    }
}
