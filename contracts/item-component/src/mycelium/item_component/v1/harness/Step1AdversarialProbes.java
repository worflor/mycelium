package mycelium.item_component.v1.harness;

import mycelium.item_component.v1.model.*;

import java.util.*;

/**
 * Independent adversarial probes reproducing the structural defects identified
 * in the external audit report (Step 1: Compositional value semantics & immutability).
 */
public final class Step1AdversarialProbes {

    public static void runAll(RosettaTranslator translator) {
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

        try { testStringArrayComponentTypePreserved(); System.out.println("  [PASS] testStringArrayComponentTypePreserved"); }
        catch (Throwable t) { System.out.println("  [FAIL] testStringArrayComponentTypePreserved: " + t.getMessage()); failures++; }

        try { testRecordMutableListDeepFreeze(); System.out.println("  [PASS] testRecordMutableListDeepFreeze"); }
        catch (Throwable t) { System.out.println("  [FAIL] testRecordMutableListDeepFreeze: " + t.getMessage()); failures++; }

        try { testCustomComponentFreezerInvoked(); System.out.println("  [PASS] testCustomComponentFreezerInvoked"); }
        catch (Throwable t) { System.out.println("  [FAIL] testCustomComponentFreezerInvoked: " + t.getMessage()); failures++; }

        try { testPatchSetValueEquality(); System.out.println("  [PASS] testPatchSetValueEquality"); }
        catch (Throwable t) { System.out.println("  [FAIL] testPatchSetValueEquality: " + t.getMessage()); failures++; }

        try { testSetComparatorSymmetryWithoutReuse(); System.out.println("  [PASS] testSetComparatorSymmetryWithoutReuse"); }
        catch (Throwable t) { System.out.println("  [FAIL] testSetComparatorSymmetryWithoutReuse: " + t.getMessage()); failures++; }

        try { testDefensiveMapJavaContracts(); System.out.println("  [PASS] testDefensiveMapJavaContracts"); }
        catch (Throwable t) { System.out.println("  [FAIL] testDefensiveMapJavaContracts: " + t.getMessage()); failures++; }

        if (failures > 0) {
            throw new AssertionError("Step 1 Adversarial Probes: " + failures + " failures detected!");
        }
        System.out.println("Step 1 Adversarial Probes: ALL PASSED");
    }

    public static void runAll() {
        runAll(RosettaTranslator.standard());
    }

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

    public static void testIngressArrayAndListAliasing() {
        ComponentId arrayId = ComponentId.of("test:in_array");
        byte[] callerArray = new byte[] { 5, 6, 7 };
        CarrierSnapshot snap1 = CarrierSnapshot.empty().with(arrayId, callerArray);
        callerArray[0] = 99;
        if (((byte[]) snap1.get(arrayId))[0] != 5) {
            throw new AssertionError("Ingress array aliasing: caller mutation altered snapshot!");
        }

        ComponentId listId = ComponentId.of("test:in_list");
        List<String> callerList = new ArrayList<>(Arrays.asList("alpha", "beta"));
        CarrierSnapshot snap2 = CarrierSnapshot.empty().with(listId, callerList);
        callerList.add("gamma");
        @SuppressWarnings("unchecked")
        List<String> snapList = (List<String>) snap2.get(listId);
        if (snapList.size() != 2) {
            throw new AssertionError("Ingress list aliasing: caller add altered snapshot list size!");
        }
    }

    public static void testListOfByteArrayEquality() {
        ComponentCodec<List<byte[]>> listCodec = ComponentCodec.listOf(ComponentCodec.BYTE_ARRAY);
        List<byte[]> l1 = Arrays.asList(new byte[]{ 1, 2 }, new byte[]{ 3 });
        List<byte[]> l2 = Arrays.asList(new byte[]{ 1, 2 }, new byte[]{ 3 });

        if (!listCodec.semanticEquals(l1, l2)) {
            throw new AssertionError("listOf(BYTE_ARRAY) failed value equality!");
        }
        if (listCodec.semanticHash(l1) != listCodec.semanticHash(l2)) {
            throw new AssertionError("listOf(BYTE_ARRAY) failed hash equality!");
        }
    }

    public static void testComponentDefinitionDefaultValueLeak() {
        byte[] defaultBytes = new byte[] { 42 };
        ComponentDefinition<byte[]> def = new ComponentDefinition<>(
                ComponentId.of("test:def_bytes"),
                ComponentCodec.BYTE_ARRAY,
                defaultBytes
        );
        defaultBytes[0] = 99;
        if (def.defaultValue()[0] != 42) {
            throw new AssertionError("ComponentDefinition defaultValue was aliased to caller's mutable array!");
        }
    }

    public static void testContradictorySnapshotConstruction() {
        ComponentId id = ComponentId.of("test:contradiction");
        try {
            new CarrierSnapshot(
                    Collections.singletonMap(id, 1),
                    Collections.emptyMap(),
                    Collections.singleton(id)
            );
            throw new AssertionError("CarrierSnapshot accepted contradictory state!");
        } catch (IllegalArgumentException expected) {
        }
    }

    public static void testRegistrySilentOverwrite() {
        ComponentId id = ComponentId.of("test:collision");
        ComponentDefinition<Integer> def1 = new ComponentDefinition<>(id, ComponentCodec.INT, 1);
        ComponentDefinition<Integer> def2 = new ComponentDefinition<>(id, ComponentCodec.INT, 2);

        try {
            ComponentRegistry.builder().register(def1).register(def2).build();
            throw new AssertionError("Registry allowed silent duplicate registration overwrite!");
        } catch (IllegalStateException expected) {
        }
    }

    public static void testStringArrayComponentTypePreserved() {
        ComponentId id = ComponentId.of("test:strings");
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, new String[] { "foo", "bar" });
        Object val = snapshot.get(id);
        if (!(val instanceof String[])) {
            throw new AssertionError("String[] array lost runtime component type and became: " + val.getClass().getName());
        }
        String[] arr = (String[]) val;
        if (arr.length != 2 || !arr[0].equals("foo")) {
            throw new AssertionError("String[] contents mismatch");
        }
    }

    public record TestContainerRecord(String title, List<String> items) {}

    public static void testRecordMutableListDeepFreeze() {
        ComponentId id = ComponentId.of("test:record");
        List<String> mutableList = new ArrayList<>(Arrays.asList("one", "two"));
        TestContainerRecord rec = new TestContainerRecord("rec", mutableList);
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, rec);

        mutableList.add("three");

        TestContainerRecord stored = (TestContainerRecord) snapshot.get(id);
        if (stored.items().size() != 2) {
            throw new AssertionError("Record with mutable list remained aliased! Stored list size: " + stored.items().size());
        }
    }

    public static void testCustomComponentFreezerInvoked() {
        ComponentId id = ComponentId.of("test:custom_freeze");
        boolean[] freezerRan = new boolean[] { false };

        ComponentCodec<int[]> customCodec = new ComponentCodec<>() {
            @Override public TagNode encode(int[] value) { return new TagNode.TagIntArray(value); }
            @Override public int[] decode(TagNode node) { return new int[0]; }
            @Override public int[] freeze(int[] value) {
                freezerRan[0] = true;
                return value.clone();
            }
        };

        ComponentDefinition<int[]> def = new ComponentDefinition<>(id, customCodec, new int[0]);
        CarrierSnapshot.empty().with(def, new int[] { 1, 2, 3 });

        if (!freezerRan[0]) {
            throw new AssertionError("Custom codec freeze() was bypassed during snapshot insertion with(ComponentDefinition, T)!");
        }
    }

    public static void testPatchSetValueEquality() {
        ComponentId id = ComponentId.of("test:patch_bytes");
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, new byte[] { 1, 2, 3 });

        ComponentPatch<byte[]> p1 = snapshot.readPatch(id);
        ComponentPatch<byte[]> p2 = snapshot.readPatch(id);

        if (!p1.equals(p2)) {
            throw new AssertionError("Repeated readPatch for array-valued component failed equals()!");
        }
        if (p1.hashCode() != p2.hashCode()) {
            throw new AssertionError("Repeated readPatch for array-valued component failed hashCode()!");
        }
    }

    public static void testSetComparatorSymmetryWithoutReuse() {
        Set<byte[]> s1 = Collections.singleton(new byte[] { 7 });
        Set<byte[]> s2 = Collections.singleton(new byte[] { 7 });

        if (!ValueSemantics.deepEquals(s1, s2)) {
            throw new AssertionError("deepEquals on Sets of arrays failed!");
        }
        if (!ValueSemantics.deepEquals(s2, s1)) {
            throw new AssertionError("deepEquals on Sets was asymmetric!");
        }
    }

    public static void testDefensiveMapJavaContracts() {
        ComponentId id = ComponentId.of("test:bytes_map");
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(id, new byte[] { 1 });

        Map<ComponentId, Object> m1 = snapshot.components();
        Map<ComponentId, Object> m2 = snapshot.components();

        Set<Map.Entry<ComponentId, Object>> e1 = m1.entrySet();
        Set<Map.Entry<ComponentId, Object>> e2 = m1.entrySet();

        if (!e1.equals(e2)) {
            throw new AssertionError("Map entrySet() failed self-equality check!");
        }
        if (!m1.equals(m1)) {
            throw new AssertionError("snapshot.components() failed self equals()!");
        }
        if (!ValueSemantics.deepEquals(m1, m2)) {
            throw new AssertionError("snapshot.components() instances failed semantic deep equality!");
        }
    }
}
