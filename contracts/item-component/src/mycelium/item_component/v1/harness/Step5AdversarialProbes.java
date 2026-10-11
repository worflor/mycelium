package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes for Step 5 & 6:
 * Logical vs Physical NBT bridges, channel policies (Persistent vs Network vs Identity),
 * and stacking equivalence laws.
 */
public final class Step5AdversarialProbes {

    public static void runAll() {
        System.out.println("Running Step 5 & 6 Adversarial Probes...");
        int failures = 0;

        try { testHeterogeneousListWrapperBridge(); System.out.println("  [PASS] testHeterogeneousListWrapperBridge"); }
        catch (Throwable t) { System.out.println("  [FAIL] testHeterogeneousListWrapperBridge: " + t.getMessage()); failures++; }

        try { testChannelPolicyNetworkFiltersTransient(); System.out.println("  [PASS] testChannelPolicyNetworkFiltersTransient"); }
        catch (Throwable t) { System.out.println("  [FAIL] testChannelPolicyNetworkFiltersTransient: " + t.getMessage()); failures++; }

        try { testStackingIdentityPreservesMeaningfulDifference(); System.out.println("  [PASS] testStackingIdentityPreservesMeaningfulDifference"); }
        catch (Throwable t) { System.out.println("  [FAIL] testStackingIdentityPreservesMeaningfulDifference: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 5 & 6 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 5 & 6 Adversarial Probes: ALL PASSED");
    }

    /**
     * Requirement 5.A:
     * Heterogeneous list bridge: logical lists with mixed types (e.g. String + Int)
     * are safely bridged to physical homogeneous compounds and un-bridged back.
     */
    public static void testHeterogeneousListWrapperBridge() {
        List<TagNode> mixed = Arrays.asList(
                TagNode.TagString.of("alpha"),
                TagNode.TagInt.of(42),
                TagNode.TagString.of("beta")
        );

        TagNode.TagList physical = LogicalNbtBridge.toPhysicalList(mixed);
        if (physical.elementTypeId() != 10) { // All wrapped elements are compounds
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

    /**
     * Requirement 6.A:
     * Channel policies: Network projection strips internal server-only diagnostics/unmapped tags
     * while Persistent projection retains them.
     */
    public static void testChannelPolicyNetworkFiltersTransient() {
        CarrierSnapshot snap = CarrierSnapshot.empty()
                .with(ComponentId.of("test:name"), "PlayerSword")
                .withUnmappedTag("server_internal:debug_marker", TagNode.TagString.of("trace_xyz"));

        CarrierSnapshot netView = ChannelPolicy.NETWORK.project(snap);
        if (netView.unmappedTags().containsKey("server_internal:debug_marker")) {
            throw new AssertionError("Network channel view must not leak server internal unmapped debug tag!");
        }
        if (!netView.getString(ComponentId.of("test:name")).map(s -> s.equals("PlayerSword")).orElse(false)) {
            throw new AssertionError("Network channel view must retain public gameplay components!");
        }

        CarrierSnapshot persistView = ChannelPolicy.PERSISTENT.project(snap);
        if (!persistView.unmappedTags().containsKey("server_internal:debug_marker")) {
            throw new AssertionError("Persistent channel view must retain unmapped tags for disk save!");
        }
    }

    /**
     * Requirement 6.C:
     * Stacking correctness: Two stacks stack if and only if their IDENTITY views are equal.
     * Items with different meaningful retained modern state must NOT stack.
     * Items with identical state must stack.
     */
    public static void testStackingIdentityPreservesMeaningfulDifference() {
        ComponentId cardId = ComponentId.of("example:card");
        CarrierSnapshot s1 = CarrierSnapshot.empty().with(cardId, "the_fool");
        CarrierSnapshot s2 = CarrierSnapshot.empty().with(cardId, "the_fool");
        CarrierSnapshot s3 = CarrierSnapshot.empty().with(cardId, "the_magician");

        if (!ChannelPolicy.IDENTITY.matches(s1, s2)) {
            throw new AssertionError("Identical carrier items must stack!");
        }
        if (ChannelPolicy.IDENTITY.matches(s1, s3)) {
            throw new AssertionError("Items with different semantic cards must NOT stack!");
        }
    }
}
