package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 1).
 */
public final class Step1AdversarialProbes {

    public static void runAll() {
        System.out.println("Running Step 1 Adversarial Probes...");
        int failures = 0;
        try { testSnapshotComponentsArrayLeak(); System.out.println("  [PASS] testSnapshotComponentsArrayLeak"); }
        catch (Throwable t) { System.out.println("  [FAIL] testSnapshotComponentsArrayLeak: " + t.getMessage()); failures++; }

        try { testIngressArrayAndListAliasing(); System.out.println("  [PASS] testIngressArrayAndListAliasing"); }
        catch (Throwable t) { System.out.println("  [FAIL] testIngressArrayAndListAliasing: " + t.getMessage()); failures++; }

        try { testListOfByteArrayEquality(); System.out.println("  [PASS] testListOfByteArrayEquality"); }
        catch (Throwable t) { System.out.println("  [FAIL] testListOfByteArrayEquality: " + t.getMessage()); failures++; }

        try { testComponentDefinitionDefaultValueLeak(); System.out.println("  [PASS] testComponentDefinitionDefaultValueLeak"); }
        catch (Throwable t) { System.out.println("  [FAIL] testComponentDefinitionDefaultValueLeak: " + t.getMessage()); failures++; }

        try { testContradictorySnapshotConstruction(); System.out.println("  [PASS] testContradictorySnapshotConstruction"); }
        catch (Throwable t) { System.out.println("  [FAIL] testContradictorySnapshotConstruction: " + t.getMessage()); failures++; }

        try { testRegistrySilentOverwrite(); System.out.println("  [PASS] testRegistrySilentOverwrite"); }
        catch (Throwable t) { System.out.println("  [FAIL] testRegistrySilentOverwrite: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 1 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 1 Adversarial Probes: ALL PASSED");
    }

    /**
     * Audit Observation A1:
     * ((byte[]) snapshot.components().get(id))[0] = 9 mutates snapshot.get(id) and hashCode().
     */
    public static void testSnapshotComponentsArrayLeak() {
        ComponentId id = ComponentId.of("test:bytes");
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, new byte[] { 1 });
        int originalHash = snapshot.hashCode();

        byte[] leaked = (byte[]) snapshot.components().get(id);
        leaked[0] = 9;

        byte[] current = (byte[]) snapshot.get(id);
        if (current[0] != 1) {
            throw new AssertionError("LEAK CONFIRMED: snapshot.get(id)[0] was mutated to " + current[0]);
        }
        if (snapshot.hashCode() != originalHash) {
            throw new AssertionError("LEAK CONFIRMED: snapshot.hashCode() mutated from " + originalHash + " to " + snapshot.hashCode());
        }
    }

    /**
     * Audit Observation A2:
     * Ingress aliasing: caller mutating passed array or list after snapshot creation.
     */
    public static void testIngressArrayAndListAliasing() {
        ComponentId idBytes = ComponentId.of("test:ingress_bytes");
        byte[] callerBytes = new byte[] { 1, 2, 3 };
        CarrierSnapshot snapshotBytes = CarrierSnapshot.empty().with(idBytes, callerBytes);
        callerBytes[0] = 99;

        byte[] storedBytes = (byte[]) snapshotBytes.get(idBytes);
        if (storedBytes[0] != 1) {
            throw new AssertionError("INGRESS LEAK: caller mutation altered snapshot byte array to " + storedBytes[0]);
        }

        ComponentId idList = ComponentId.of("test:ingress_list");
        List<String> callerList = new ArrayList<>(Arrays.asList("alpha", "beta"));
        CarrierSnapshot snapshotList = CarrierSnapshot.empty().with(idList, callerList);
        callerList.add("gamma");

        @SuppressWarnings("unchecked")
        List<String> storedList = (List<String>) snapshotList.get(idList);
        if (storedList.size() != 2) {
            throw new AssertionError("INGRESS LEAK: caller list mutation altered snapshot list size to " + storedList.size());
        }
    }

    /**
     * Audit Observation A3:
     * ComponentCodec.listOf(ComponentCodec.BYTE_ARRAY) fails CarrierSnapshot round-trip equality.
     */
    public static void testListOfByteArrayEquality() {
        ComponentId id = ComponentId.of("test:list_of_bytes");
        ComponentCodec<List<byte[]>> codec = ComponentCodec.listOf(ComponentCodec.BYTE_ARRAY);
        ComponentRegistry registry = ComponentRegistry.builder()
                .register(id, codec, Collections.emptyList())
                .build();

        List<byte[]> list1 = Arrays.asList(new byte[] { 1, 2 }, new byte[] { 3, 4 });
        CarrierSnapshot snap1 = CarrierSnapshot.empty().with(id, list1);

        RosettaTranslator translator = RosettaTranslator.standard();
        TagNode.TagCompound nbt = translator.toLegacyNbt(snap1, registry);
        CarrierSnapshot snap2 = translator.fromLegacyNbt(nbt, registry);

        if (!snap1.equals(snap2)) {
            throw new AssertionError("EQUALITY BUG: snap1.equals(snap2) failed for listOf(BYTE_ARRAY)!");
        }
        if (snap1.hashCode() != snap2.hashCode()) {
            throw new AssertionError("HASH BUG: snap1.hashCode() != snap2.hashCode() for listOf(BYTE_ARRAY)!");
        }
    }

    /**
     * Audit Observation A4:
     * ComponentDefinition retains caller-owned defaultValue directly without freezing.
     */
    public static void testComponentDefinitionDefaultValueLeak() {
        ComponentId id = ComponentId.of("test:def_leak");
        byte[] defaultBytes = new byte[] { 42 };
        ComponentDefinition<byte[]> def = new ComponentDefinition<>(id, ComponentCodec.BYTE_ARRAY, defaultBytes);

        defaultBytes[0] = 99;
        if (def.defaultValue()[0] != 42) {
            throw new AssertionError("DEF LEAK: mutating caller default array altered def.defaultValue()!");
        }

        byte[] readDefault = def.defaultValue();
        readDefault[0] = 77;
        if (def.defaultValue()[0] != 42) {
            throw new AssertionError("DEF LEAK: mutating def.defaultValue() return value altered def.defaultValue()!");
        }
    }

    /**
     * Audit Observation D1:
     * Public constructor admits component in both components and removals.
     */
    public static void testContradictorySnapshotConstruction() {
        ComponentId id = ComponentId.of("test:contradiction");
        Map<ComponentId, Object> comps = Collections.singletonMap(id, 10);
        Set<ComponentId> rems = Collections.singleton(id);

        try {
            new CarrierSnapshot(comps, Collections.emptyMap(), rems);
            throw new AssertionError("CONTRADICTION NOT REJECTED: allowed component in both components and removals!");
        } catch (IllegalArgumentException expected) {
            // Success: contradictory state rejected
        }
    }

    /**
     * Audit Observation D2:
     * Registry registration silently replaces existing definition.
     */
    public static void testRegistrySilentOverwrite() {
        ComponentId id = ComponentId.of("test:conflict");
        ComponentDefinition<Integer> def1 = new ComponentDefinition<>(id, ComponentCodec.INT, 1);
        ComponentDefinition<String> def2 = new ComponentDefinition<>(id, ComponentCodec.STRING, "duplicate");

        try {
            ComponentRegistry.builder()
                    .register(def1)
                    .register(def2)
                    .build();
            throw new AssertionError("SILENT OVERWRITE: registry accepted conflicting definition without exception!");
        } catch (IllegalStateException expected) {
            // Success: conflicting registration rejected
        }
    }
}
