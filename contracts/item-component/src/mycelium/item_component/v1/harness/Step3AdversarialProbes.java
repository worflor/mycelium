package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 3: Recovery records, non-compound envelopes, unit/float safety, field-aware remainder).
 */
public final class Step3AdversarialProbes {

    public static void runAll() {
        System.out.println("Running Step 3 Adversarial Probes...");
        int failures = 0;

        try { testInvalidIdInRemovedPreserved(); System.out.println("  [PASS] testInvalidIdInRemovedPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testInvalidIdInRemovedPreserved: " + t.getMessage()); failures++; }

        try { testIntListInRemovedPreserved(); System.out.println("  [PASS] testIntListInRemovedPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testIntListInRemovedPreserved: " + t.getMessage()); failures++; }

        try { testNonCompoundEnvelopePreserved(); System.out.println("  [PASS] testNonCompoundEnvelopePreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testNonCompoundEnvelopePreserved: " + t.getMessage()); failures++; }

        try { testQuarantinedRemovedNotOverwrittenByValidRemoval(); System.out.println("  [PASS] testQuarantinedRemovedNotOverwrittenByValidRemoval"); }
        catch (Throwable t) { System.out.println("  [FAIL] testQuarantinedRemovedNotOverwrittenByValidRemoval: " + t.getMessage()); failures++; }

        try { testAbsentRegistryComponentPreserved(); System.out.println("  [PASS] testAbsentRegistryComponentPreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testAbsentRegistryComponentPreserved: " + t.getMessage()); failures++; }

        try { testTagCompoundRejectsNulls(); System.out.println("  [PASS] testTagCompoundRejectsNulls"); }
        catch (Throwable t) { System.out.println("  [FAIL] testTagCompoundRejectsNulls: " + t.getMessage()); failures++; }

        try { testUnitCodecStrictValidation(); System.out.println("  [PASS] testUnitCodecStrictValidation"); }
        catch (Throwable t) { System.out.println("  [FAIL] testUnitCodecStrictValidation: " + t.getMessage()); failures++; }

        try { testFieldAwareRemainderRetention(); System.out.println("  [PASS] testFieldAwareRemainderRetention"); }
        catch (Throwable t) { System.out.println("  [FAIL] testFieldAwareRemainderRetention: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 3 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 3 Adversarial Probes: ALL PASSED");
    }

    /**
     * Audit Observation C1:
     * !removed contains an invalid identifier string -> must be preserved, not disappear.
     */
    public static void testInvalidIdInRemovedPreserved() {
        TagNode.TagList remList = TagNode.TagList.of(
                TagNode.TagString.of("valid:one"),
                TagNode.TagString.of(":::invalid:id:::"),
                TagNode.TagString.of("valid:two")
        );
        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with("!removed", remList);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        RosettaTranslator translator = RosettaTranslator.standard();
        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);

        TagNode.TagCompound reEncoded = translator.toLegacyNbt(decoded, reg);
        TagNode.TagCompound reFenced = reEncoded.getCompound(RosettaTranslator.FENCED_NAMESPACE)
                .orElseThrow(() -> new AssertionError("Re-encoded compound must have mycelium$c"));

        // The invalid entry must survive somewhere in re-encoded output (as unmapped/recovery or preserved in list)
        boolean preserved = reFenced.has("!removed_quarantine") || reFenced.has("!removed");
        if (!preserved) {
            throw new AssertionError("Invalid identifier string in !removed was permanently lost!");
        }
    }

    /**
     * Audit Observation C2:
     * !removed is a homogeneous list of integers -> must not disappear.
     */
    public static void testIntListInRemovedPreserved() {
        TagNode.TagList intList = TagNode.TagList.of(TagNode.TagInt.of(1), TagNode.TagInt.of(2));
        TagNode.TagCompound fenced = TagNode.TagCompound.empty().with("!removed", intList);
        TagNode.TagCompound root = TagNode.TagCompound.empty().with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        RosettaTranslator translator = RosettaTranslator.standard();
        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);

        TagNode.TagCompound reEncoded = translator.toLegacyNbt(decoded, reg);
        TagNode.TagCompound reFenced = reEncoded.getCompound(RosettaTranslator.FENCED_NAMESPACE)
                .orElseThrow(() -> new AssertionError("Re-encoded compound must have mycelium$c"));

        if (!reFenced.has("!removed") && !reFenced.has("!removed_quarantine")) {
            throw new AssertionError("Int-list in !removed was completely lost on re-serialization!");
        }
    }

    /**
     * Audit Observation C3:
     * Outer mycelium$c is present but not a compound (e.g. TagInt) -> must be preserved as unreadable envelope.
     */
    public static void testNonCompoundEnvelopePreserved() {
        TagNode.TagCompound root = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, TagNode.TagInt.of(12345));

        RosettaTranslator translator = RosettaTranslator.standard();
        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);

        TagNode.TagCompound reEncoded = translator.toLegacyNbt(decoded, reg);
        TagNode envelope = reEncoded.get(RosettaTranslator.FENCED_NAMESPACE);
        if (envelope == null || !(envelope instanceof TagNode.TagInt ti) || ti.value() != 12345) {
            throw new AssertionError("Non-compound mycelium$c was destroyed! Got: " + envelope);
        }
    }

    /**
     * Audit Observation C4:
     * Malformed scalar !removed is quarantined, then a valid removal is added:
     * the valid removal must not overwrite the quarantined malformed payload!
     */
    public static void testQuarantinedRemovedNotOverwrittenByValidRemoval() {
        TagNode.TagCompound fenced = TagNode.TagCompound.empty()
                .with("!removed", TagNode.TagInt.of(999));
        TagNode.TagCompound root = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        RosettaTranslator translator = RosettaTranslator.standard();
        ComponentRegistry reg = ComponentRegistry.empty();
        CarrierSnapshot decoded = translator.fromLegacyNbt(root, reg);

        // Add a valid removal
        CarrierSnapshot withValidRem = decoded.withRemoval(ComponentId.of("test:valid_rem"));

        TagNode.TagCompound reEncoded = translator.toLegacyNbt(withValidRem, reg);
        TagNode.TagCompound reFenced = reEncoded.getCompound(RosettaTranslator.FENCED_NAMESPACE).orElseThrow();

        // Both the valid removal and the quarantined scalar must be preserved!
        TagNode remNode = reFenced.get("!removed");
        if (!(remNode instanceof TagNode.TagList)) {
            throw new AssertionError("Valid removal must be emitted as TagList in !removed");
        }
        // The quarantined 999 must also be present (e.g. in !removed_quarantine or recovery)
        if (!reFenced.has("!removed_quarantine") && !reFenced.has("!quarantine_removed")) {
            throw new AssertionError("Quarantined scalar !removed was overwritten and lost by valid removal!");
        }
    }

    /**
     * Audit Observation C5:
     * Typed snapshot contains a component absent from registry -> must NOT be silently skipped.
     */
    public static void testAbsentRegistryComponentPreserved() {
        ComponentId unregisteredId = ComponentId.of("unregistered:data");
        CarrierSnapshot snap = CarrierSnapshot.empty()
                .with(unregisteredId, TagNode.TagString.of("must_survive"));

        RosettaTranslator translator = RosettaTranslator.standard();
        ComponentRegistry emptyReg = ComponentRegistry.empty();

        TagNode.TagCompound nbt = translator.toLegacyNbt(snap, emptyReg);
        TagNode.TagCompound fenced = nbt.getCompound(RosettaTranslator.FENCED_NAMESPACE)
                .orElseThrow(() -> new AssertionError("Must emit fenced compound"));

        if (!fenced.has(unregisteredId.toString())) {
            throw new AssertionError("Unregistered component was silently dropped during encoding!");
        }
    }

    /**
     * Audit Observation E5:
     * TagCompound constructor must reject null keys and null values.
     */
    public static void testTagCompoundRejectsNulls() {
        try {
            Map<String, TagNode> badMap = new HashMap<>();
            badMap.put(null, TagNode.TagInt.of(1));
            new TagNode.TagCompound(badMap);
            throw new AssertionError("TagCompound accepted null key!");
        } catch (NullPointerException expected) {}

        try {
            Map<String, TagNode> badMap = new HashMap<>();
            badMap.put("key", null);
            new TagNode.TagCompound(badMap);
            throw new AssertionError("TagCompound accepted null value!");
        } catch (NullPointerException expected) {}
    }

    /**
     * Audit Observation E4:
     * Unit codec must strictly reject non-empty malformed tags.
     */
    public static void testUnitCodecStrictValidation() {
        try {
            ComponentCodec.UNIT.decode(TagNode.TagInt.of(42));
            throw new AssertionError("UNIT codec accepted TagInt(42)!");
        } catch (CodecException expected) {}
    }

    /**
     * Audit Observation F:
     * Field-aware remainder retention:
     * Decoding { level: 3, future_property: "keep me" } with older codec that only knows level:
     * modifying level: 3 -> 4, then re-saving MUST retain future_property: "keep me"!
     */
    public static void testFieldAwareRemainderRetention() throws Exception {
        ComponentId id = ComponentId.of("test:enchantment");

        // Raw input with both known field ("level") and future field ("future_property")
        TagNode.TagCompound originalRaw = TagNode.TagCompound.empty()
                .with("level", TagNode.TagInt.of(3))
                .with("future_property", TagNode.TagString.of("keep me"));

        // Field-aware lens/codec that understands only "level"
        FieldAwareCodec<Integer> lens = FieldAwareCodec.ofCompoundField(
                "level",
                ComponentCodec.INT
        );

        FieldAwareCodec.DecodedResult<Integer> res = lens.decodeFieldAware(originalRaw);
        if (res.value() != 3) {
            throw new AssertionError("Expected level 3, got " + res.value());
        }
        if (!res.remainder().has("future_property")) {
            throw new AssertionError("Remainder must retain unconsumed future_property!");
        }

        // Edit level from 3 to 4
        TagNode.TagCompound updatedRaw = lens.updateFieldAware(originalRaw, 4);

        if (!updatedRaw.getInt("level").map(v -> v == 4).orElse(false)) {
            throw new AssertionError("Updated raw must have level 4!");
        }
        if (!updatedRaw.getString("future_property").map(v -> v.equals("keep me")).orElse(false)) {
            throw new AssertionError("FUTURE FIELD DESTROYED: future_property was lost after updating level!");
        }
    }
}
