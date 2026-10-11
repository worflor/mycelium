package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 3: Recovery records, non-compound envelopes, unit/float safety, field-aware remainder).
 */
public final class Step3AdversarialProbes {

    public static void runAll(RosettaTranslator translator) {
        System.out.println("Running Step 3 Adversarial Probes...");
        int failures = 0;

        try { testInvalidIdInRemovedPreserved(translator); System.out.println("  [PASS] testInvalidIdInRemovedPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testInvalidIdInRemovedPreserved: " + t.getMessage()); failures++; }

        try { testIntListInRemovedPreserved(translator); System.out.println("  [PASS] testIntListInRemovedPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testIntListInRemovedPreserved: " + t.getMessage()); failures++; }

        try { testNonCompoundEnvelopePreserved(translator); System.out.println("  [PASS] testNonCompoundEnvelopePreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testNonCompoundEnvelopePreserved: " + t.getMessage()); failures++; }

        try { testUnreadableScalarEnvelopeEditLifecycle(translator); System.out.println("  [PASS] testUnreadableScalarEnvelopeEditLifecycle"); }
        catch (Throwable t) { System.out.println("  [FAIL] testUnreadableScalarEnvelopeEditLifecycle: " + t.getMessage()); failures++; }

        try { testQuarantinedRemovedNotOverwrittenByValidRemoval(translator); System.out.println("  [PASS] testQuarantinedRemovedNotOverwrittenByValidRemoval"); }
        catch (Throwable t) { System.out.println("  [FAIL] testQuarantinedRemovedNotOverwrittenByValidRemoval: " + t.getMessage()); failures++; }

        try { testAbsentRegistryComponentPreserved(translator); System.out.println("  [PASS] testAbsentRegistryComponentPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testAbsentRegistryComponentPreserved: " + t.getMessage()); failures++; }

        try { testStringValueOfFallbackRejected(translator); System.out.println("  [PASS] testStringValueOfFallbackRejected"); }
        catch (Throwable t) { System.out.println("  [FAIL] testStringValueOfFallbackRejected: " + t.getMessage()); failures++; }

        try { testTagCompoundRejectsNulls(); System.out.println("  [PASS] testTagCompoundRejectsNulls"); }
        catch (Throwable t) { System.out.println("  [FAIL] testTagCompoundRejectsNulls: " + t.getMessage()); failures++; }

        try { testUnitCodecStrictValidation(); System.out.println("  [PASS] testUnitCodecStrictValidation"); }
        catch (Throwable t) { System.out.println("  [FAIL] testUnitCodecStrictValidation: " + t.getMessage()); failures++; }

        try { testFloatDoubleOverflowRejection(); System.out.println("  [PASS] testFloatDoubleOverflowRejection"); }
        catch (Throwable t) { System.out.println("  [FAIL] testFloatDoubleOverflowRejection: " + t.getMessage()); failures++; }

        try { testCustomDecoderNullSafelyQuarantined(translator); System.out.println("  [PASS] testCustomDecoderNullSafelyQuarantined"); }
        catch (Throwable t) { System.out.println("  [FAIL] testCustomDecoderNullSafelyQuarantined: " + t.getMessage()); failures++; }

        try { testCodecValidateEnforced(translator); System.out.println("  [PASS] testCodecValidateEnforced"); }
        catch (Throwable t) { System.out.println("  [FAIL] testCodecValidateEnforced: " + t.getMessage()); failures++; }

        try { testFieldAwareRemainderRetention(); System.out.println("  [PASS] testFieldAwareRemainderRetention"); }
        catch (Throwable t) { System.out.println("  [FAIL] testFieldAwareRemainderRetention: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 3 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 3 Adversarial Probes: ALL PASSED");
    }

    public static void runAll() {
        runAll(RosettaTranslator.standard());
    }

    public static void testInvalidIdInRemovedPreserved(RosettaTranslator translator) {
        TagNode.TagList remList = TagNode.TagList.of(
                TagNode.TagString.of("valid:one"),
                TagNode.TagString.of(":::invalid:id:::"),
                TagNode.TagString.of("valid:two")
        );
        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with("!removed", remList);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot snap = translator.fromLegacyNbt(root, reg);

        if (!snap.isRemoved(ComponentId.of("valid:one")) || !snap.isRemoved(ComponentId.of("valid:two"))) {
            throw new AssertionError("Valid removal IDs were not registered!");
        }

        TagNode.TagCompound reSaved = translator.toLegacyNbt(snap, reg);
        CarrierSnapshot reloaded = translator.fromLegacyNbt(reSaved, reg);

        if (!reloaded.isRemoved(ComponentId.of("valid:one"))) {
            throw new AssertionError("Valid removals lost on re-save!");
        }
    }

    public static void testIntListInRemovedPreserved(RosettaTranslator translator) {
        TagNode.TagList intList = TagNode.TagList.of(
                TagNode.TagInt.of(1), TagNode.TagInt.of(2), TagNode.TagInt.of(3)
        );
        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with("!removed", intList);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot snap = translator.fromLegacyNbt(root, reg);

        TagNode.TagCompound reSaved = translator.toLegacyNbt(snap, reg);
        CarrierSnapshot reloaded = translator.fromLegacyNbt(reSaved, reg);

        boolean preserved = reloaded.unmappedTags().containsKey("!removed_quarantine")
                || !reloaded.recoveryRecords().isEmpty();
        if (!preserved) {
            throw new AssertionError("Integer list in !removed was permanently lost!");
        }
    }

    public static void testNonCompoundEnvelopePreserved(RosettaTranslator translator) {
        TagNode scalarEnvelope = TagNode.TagInt.of(999);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, scalarEnvelope);

        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot snap = translator.fromLegacyNbt(root, reg);

        TagNode.TagCompound reSaved = translator.toLegacyNbt(snap, reg);
        TagNode reSavedEnvelope = reSaved.get(RosettaTranslator.FENCED_NAMESPACE);

        if (!Objects.equals(scalarEnvelope, reSavedEnvelope)) {
            throw new AssertionError("Non-compound scalar envelope was destroyed!");
        }
    }

    public static void testUnreadableScalarEnvelopeEditLifecycle(RosettaTranslator translator) {
        TagNode scalarEnvelope = TagNode.TagInt.of(42);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, scalarEnvelope);

        ComponentId validId = ComponentId.of("test:valid_entry");
        ComponentRegistry reg = ComponentRegistry.builder().register(validId, ComponentCodec.INT, 0).build();

        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);
        CarrierSnapshot edited = decoded.with(validId, 7);

        TagNode.TagCompound saved = translator.toLegacyNbt(edited, reg);
        CarrierSnapshot reloaded = translator.fromLegacyNbt(saved, reg);

        if (!reloaded.has(validId) || !Objects.equals(reloaded.get(validId), 7)) {
            throw new AssertionError("Valid component added to scalar envelope was lost!");
        }
        if (reloaded.rawEnvelope().isEmpty() || !Objects.equals(reloaded.rawEnvelope().get(), scalarEnvelope)) {
            throw new AssertionError("Original unreadable scalar envelope was lost after adding a valid component!");
        }
    }

    public static void testQuarantinedRemovedNotOverwrittenByValidRemoval(RosettaTranslator translator) {
        TagNode.TagCompound fenced = TagNode.TagCompound.empty()
                .with("!removed", TagNode.TagInt.of(42));
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot snap = translator.fromLegacyNbt(root, reg);

        CarrierSnapshot edited = snap.withRemoval(ComponentId.of("valid:new_removal"));

        TagNode.TagCompound reSaved = translator.toLegacyNbt(edited, reg);
        CarrierSnapshot reloaded = translator.fromLegacyNbt(reSaved, reg);

        if (!reloaded.isRemoved(ComponentId.of("valid:new_removal"))) {
            throw new AssertionError("Valid removal was lost!");
        }
        boolean quarantineSurvives = reloaded.unmappedTags().containsKey("!removed_quarantine")
                || !reloaded.recoveryRecords().isEmpty();
        if (!quarantineSurvives) {
            throw new AssertionError("Quarantined scalar !removed was overwritten by valid removal!");
        }
    }

    public static void testAbsentRegistryComponentPreserved(RosettaTranslator translator) {
        ComponentId unregisteredId = ComponentId.of("unregistered:data");
        CarrierSnapshot snap = CarrierSnapshot.empty()
                .with(unregisteredId, TagNode.TagString.of("must_survive"));

        ComponentRegistry emptyReg = ComponentRegistry.empty();

        TagNode.TagCompound nbt = translator.toLegacyNbt(snap, emptyReg);
        TagNode.TagCompound fenced = nbt.getCompound(RosettaTranslator.FENCED_NAMESPACE)
                .orElseThrow(() -> new AssertionError("Must emit fenced compound"));

        if (!fenced.has(unregisteredId.toString())) {
            throw new AssertionError("Unregistered component was silently dropped during encoding!");
        }
    }

    public static void testStringValueOfFallbackRejected(RosettaTranslator translator) {
        ComponentId id = ComponentId.of("unregistered:bytes");
        CarrierSnapshot snap = CarrierSnapshot.empty().with(id, new byte[] { 1, 2, 3 });
        ComponentRegistry emptyReg = ComponentRegistry.empty();

        try {
            translator.toLegacyNbt(snap, emptyReg);
            throw new AssertionError("Serializing an unregistered arbitrary Java object should fail rather than emit lossy String.valueOf string slop!");
        } catch (IllegalArgumentException expected) {
        }
    }

    public static void testTagCompoundRejectsNulls() {
        try {
            Map<String, TagNode> badMap = new HashMap<>();
            badMap.put(null, TagNode.TagInt.of(1));
            new TagNode.TagCompound(badMap);
            throw new AssertionError("TagCompound accepted null key!");
        } catch (NullPointerException expected) {
        }

        try {
            Map<String, TagNode> badMap = new HashMap<>();
            badMap.put("key", null);
            new TagNode.TagCompound(badMap);
            throw new AssertionError("TagCompound accepted null value!");
        } catch (NullPointerException expected) {
        }
    }

    public static void testUnitCodecStrictValidation() {
        try {
            ComponentCodec.UNIT.decode(TagNode.TagInt.of(42));
            throw new AssertionError("UNIT codec accepted TagInt(42)!");
        } catch (CodecException expected) {
        }

        try {
            ComponentCodec.UNIT.decode(TagNode.TagCompound.empty().with("foo", TagNode.TagInt.of(1)));
            throw new AssertionError("UNIT codec accepted non-empty compound!");
        } catch (CodecException expected) {
        }
    }

    public static void testFloatDoubleOverflowRejection() {
        TagNode.TagDouble hugeDouble = new TagNode.TagDouble(Double.MAX_VALUE);
        try {
            ComponentCodec.FLOAT.decode(hugeDouble);
            throw new AssertionError("FLOAT codec silently converted Double.MAX_VALUE to infinity instead of rejecting overflow!");
        } catch (CodecException expected) {
        }
    }

    public static void testCustomDecoderNullSafelyQuarantined(RosettaTranslator translator) {
        ComponentId id = ComponentId.of("test:null_decoder");
        ComponentCodec<String> nullReturningCodec = new ComponentCodec<>() {
            @Override public TagNode encode(String value) { return TagNode.TagString.of(value); }
            @Override public String decode(TagNode node) { return null; }
        };

        ComponentDefinition<String> def = new ComponentDefinition<>(id, nullReturningCodec, "");
        ComponentRegistry reg = ComponentRegistry.builder().register(def).build();

        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with(id.toString(), TagNode.TagString.of("test"));
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);
        if (decoded.has(id)) {
            throw new AssertionError("Snapshot accepted null decoded value!");
        }
        if (!decoded.unmappedTags().containsKey(id.toString())) {
            throw new AssertionError("Node returning null from decoder was not safely quarantined in unmappedTags!");
        }
    }

    public static void testCodecValidateEnforced(RosettaTranslator translator) {
        ComponentId id = ComponentId.of("test:validated_int");
        ComponentCodec<Integer> positiveIntCodec = new ComponentCodec<>() {
            @Override public TagNode encode(Integer value) { return TagNode.TagInt.of(value); }
            @Override public Integer decode(TagNode node) throws CodecException {
                if (node instanceof TagNode.TagInt ti) return ti.value();
                throw new CodecException("Expected TagInt");
            }
            @Override public void validate(Integer value) throws CodecException {
                if (value != null && value < 0) throw new CodecException("Negative integers disallowed: " + value);
            }
        };

        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, positiveIntCodec, 0);
        ComponentRegistry reg = ComponentRegistry.builder().register(def).build();

        CarrierSnapshot invalidSnap = CarrierSnapshot.empty().with(id, -10);
        try {
            translator.toLegacyNbt(invalidSnap, reg);
            throw new AssertionError("Encoder failed to enforce codec.validate()!");
        } catch (IllegalArgumentException expected) {
        }

        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with(id.toString(), TagNode.TagInt.of(-10));
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);
        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);
        if (decoded.has(id)) {
            throw new AssertionError("Decoder failed to enforce codec.validate() and accepted invalid value!");
        }
    }

    public static void testFieldAwareRemainderRetention() {
        FieldAwareCodec<Integer> lens = FieldAwareCodec.ofCompoundField("level", ComponentCodec.INT);

        TagNode.TagCompound initial = TagNode.TagCompound.empty()
                .with("level", TagNode.TagInt.of(1))
                .with("future_mod:rune", TagNode.TagString.of("starlight"))
                .with("future_mod:charges", TagNode.TagInt.of(5));

        try {
            FieldAwareCodec.DecodedResult<Integer> res = lens.decodeFieldAware(initial);
            if (res.value() != 1) {
                throw new AssertionError("Decoded value mismatch: " + res.value());
            }
            TagNode.TagCompound updated = lens.updateFieldAware(initial, 2);
            if (updated.getInt("level").orElse(0) != 2) {
                throw new AssertionError("Updated field mismatch");
            }
            if (!updated.getString("future_mod:rune").orElse("").equals("starlight")
                    || updated.getInt("future_mod:charges").orElse(0) != 5) {
                throw new AssertionError("Field-aware codec destroyed unconsumed remainder tags!");
            }
        } catch (CodecException e) {
            throw new AssertionError("FieldAwareCodec threw exception: " + e.getMessage());
        }
    }
}
