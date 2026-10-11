package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes for Step 5 & 6:
 * Logical vs Physical NBT bridges, channel policies (Persistent vs Network vs Identity),
 * and stacking equivalence laws.
 */
public final class Step5AdversarialProbes {

    public static void runAll(RosettaTranslator translator) {
        System.out.println("Running Step 5 & 6 Adversarial Probes...");
        int failures = 0;

        try { testHeterogeneousListWrapperBridge(); System.out.println("  [PASS] testHeterogeneousListWrapperBridge"); }
        catch (Throwable t) { System.out.println("  [FAIL] testHeterogeneousListWrapperBridge: " + t.getMessage()); failures++; }

        try { testMojangNativeListEncodingConformance(); System.out.println("  [PASS] testMojangNativeListEncodingConformance"); }
        catch (Throwable t) { System.out.println("  [FAIL] testMojangNativeListEncodingConformance: " + t.getMessage()); failures++; }

        try { testHeterogeneousListPreservesSingleKeyCompounds(); System.out.println("  [PASS] testHeterogeneousListPreservesSingleKeyCompounds"); }
        catch (Throwable t) { System.out.println("  [FAIL] testHeterogeneousListPreservesSingleKeyCompounds: " + t.getMessage()); failures++; }

        try { testChannelPolicyNetworkFiltersTypedServerInternal(); System.out.println("  [PASS] testChannelPolicyNetworkFiltersTypedServerInternal"); }
        catch (Throwable t) { System.out.println("  [FAIL] testChannelPolicyNetworkFiltersTypedServerInternal: " + t.getMessage()); failures++; }

        try { testEffectivePolicyDistinguishesRemovalFromInheritedDefault(); System.out.println("  [PASS] testEffectivePolicyDistinguishesRemovalFromInheritedDefault"); }
        catch (Throwable t) { System.out.println("  [FAIL] testEffectivePolicyDistinguishesRemovalFromInheritedDefault: " + t.getMessage()); failures++; }

        try { testStackingIdentityPreservesMeaningfulRecoveryDistinctions(); System.out.println("  [PASS] testStackingIdentityPreservesMeaningfulRecoveryDistinctions"); }
        catch (Throwable t) { System.out.println("  [FAIL] testStackingIdentityPreservesMeaningfulRecoveryDistinctions: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 5 & 6 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 5 & 6 Adversarial Probes: ALL PASSED");
    }

    public static void runAll() {
        runAll(RosettaTranslator.standard());
    }

    public static void testHeterogeneousListWrapperBridge() {
        List<TagNode> mixed = Arrays.asList(
                TagNode.TagString.of("alpha"),
                TagNode.TagInt.of(42),
                TagNode.TagString.of("beta")
        );

        TagNode.TagList physical = LogicalNbtBridge.toPhysicalList(mixed);
        if (physical.elementTypeId() != 10) {
            throw new AssertionError("Physical list for heterogeneous elements must be wrapped into homogeneous compounds! Got typeId: " + physical.elementTypeId());
        }

        List<TagNode> unbridged = LogicalNbtBridge.toLogicalList(physical);
        if (unbridged.size() != 3) {
            throw new AssertionError("Unbridged list size mismatch: " + unbridged.size());
        }
        if (!Objects.equals(unbridged.get(0), TagNode.TagString.of("alpha"))
                || !Objects.equals(unbridged.get(1), TagNode.TagInt.of(42))
                || !Objects.equals(unbridged.get(2), TagNode.TagString.of("beta"))) {
            throw new AssertionError("Unbridged logical elements do not match original mixed list!");
        }
    }

    public static void testMojangNativeListEncodingConformance() {
        // Mojang 1.21.5 example: ['a', {'b': 3}] -> [{'': 'a'}, {'b': 3}]
        List<TagNode> logical = Arrays.asList(
                TagNode.TagString.of("a"),
                TagNode.TagCompound.empty().with("b", TagNode.TagInt.of(3))
        );

        TagNode.TagList physical = LogicalNbtBridge.toNativeMojangList(logical);
        if (physical.size() != 2 || physical.elementTypeId() != 10) {
            throw new AssertionError("Expected physical TagList of TagCompounds!");
        }

        TagNode.TagCompound firstElem = (TagNode.TagCompound) physical.get(0);
        if (!firstElem.has("") || !Objects.equals(firstElem.get(""), TagNode.TagString.of("a"))) {
            throw new AssertionError("Non-compound element was not wrapped with empty key '': " + firstElem);
        }

        TagNode.TagCompound secondElem = (TagNode.TagCompound) physical.get(1);
        if (secondElem.has("") || !Objects.equals(secondElem.get("b"), TagNode.TagInt.of(3))) {
            throw new AssertionError("Compound element was erroneously altered: " + secondElem);
        }

        List<TagNode> recovered = LogicalNbtBridge.fromNativeMojangList(physical);
        if (recovered.size() != 2
                || !Objects.equals(recovered.get(0), TagNode.TagString.of("a"))
                || !Objects.equals(recovered.get(1), TagNode.TagCompound.empty().with("b", TagNode.TagInt.of(3)))) {
            throw new AssertionError("Failed to recover logical list from Mojang native physical format!");
        }
    }

    public static void testHeterogeneousListPreservesSingleKeyCompounds() {
        TagNode.TagCompound singleKeyCompound = TagNode.TagCompound.empty()
                .with("\u00A7w", TagNode.TagInt.of(1))
                .with("keep", TagNode.TagString.of("payload"));

        List<TagNode> list = Collections.singletonList(singleKeyCompound);
        TagNode.TagList physical = LogicalNbtBridge.toPhysicalList(list);
        List<TagNode> unbridged = LogicalNbtBridge.toLogicalList(physical);

        if (unbridged.size() != 1 || !(unbridged.get(0) instanceof TagNode.TagCompound tc) || !tc.has("keep")) {
            throw new AssertionError("Legitimate compound with special key lost 'keep' subfield during unbridging!");
        }
    }

    public static void testChannelPolicyNetworkFiltersTypedServerInternal() {
        ComponentId publicId = ComponentId.of("gameplay:level");
        ComponentId secretId = ComponentId.of("server_internal:secret_token");

        ComponentDefinition<Integer> pubDef = new ComponentDefinition<>(publicId, ComponentCodec.INT, 1);
        ComponentDefinition<String> secDef = new ComponentDefinition<>(secretId, ComponentCodec.STRING, "").withNetworkSynced(false);

        ComponentRegistry reg = ComponentRegistry.builder().register(pubDef).register(secDef).build();

        CarrierSnapshot snap = CarrierSnapshot.empty()
                .with(publicId, 10)
                .with(secretId, "top_secret");

        CarrierSnapshot netView = ChannelPolicy.NETWORK.project(snap, reg, ItemPrototype.empty());

        if (netView.has(secretId)) {
            throw new AssertionError("Network channel view leaked typed server_internal component!");
        }
        if (!netView.has(publicId) || !Objects.equals(netView.get(publicId), 10)) {
            throw new AssertionError("Network channel view dropped public gameplay component!");
        }
    }

    public static void testEffectivePolicyDistinguishesRemovalFromInheritedDefault() {
        ComponentId id = ComponentId.of("test:speed");
        ComponentDefinition<Integer> def = new ComponentDefinition<>(id, ComponentCodec.INT, 0);
        ComponentRegistry reg = ComponentRegistry.builder().register(def).build();

        ItemPrototype prototype = ItemPrototype.builder().withBaseComponent(id, 100).build();

        CarrierSnapshot sInherit = CarrierSnapshot.empty(); // inherits 100
        CarrierSnapshot sRemoved = CarrierSnapshot.empty().withRemoval(id); // explicit removal

        if (ChannelPolicy.EFFECTIVE.matches(sInherit, sRemoved, reg, prototype)) {
            throw new AssertionError("EFFECTIVE channel policy erroneously treated an untouched patch and explicit removal as equal!");
        }
    }

    public static void testStackingIdentityPreservesMeaningfulRecoveryDistinctions() {
        ComponentId cardId = ComponentId.of("example:card");
        CarrierSnapshot s1 = CarrierSnapshot.empty()
                .with(cardId, "the_fool")
                .withRawEnvelope(TagNode.TagInt.of(1));

        CarrierSnapshot s2 = CarrierSnapshot.empty()
                .with(cardId, "the_fool")
                .withRawEnvelope(TagNode.TagInt.of(2));

        if (ChannelPolicy.IDENTITY.matches(s1, s2)) {
            throw new AssertionError("Items with different unreadable envelopes erroneously matched under IDENTITY policy!");
        }

        CarrierSnapshot sIdentical = CarrierSnapshot.empty()
                .with(cardId, "the_fool")
                .withRawEnvelope(TagNode.TagInt.of(1));

        if (!ChannelPolicy.IDENTITY.matches(s1, sIdentical)) {
            throw new AssertionError("Identical items with same unreadable envelope failed to match under IDENTITY policy!");
        }
    }
}
