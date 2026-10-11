package mycelium.item_component.v1.harness;

import mycelium.harness.Check;
import mycelium.harness.Seeded;
import mycelium.item_component.v1.model.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * item.component@1 contract harness tests (SPEC.md).
 */
public final class ItemComponentContractTests {
    private final Supplier<RosettaTranslator> translators;

    public ItemComponentContractTests(Supplier<RosettaTranslator> translators) {
        this.translators = translators;
    }

    public Check run() {
        Check c = new Check();
        t1RoundTripIsomorphism(c);
        t2DefaultElision(c);
        t3NamespaceFencing(c);
        t4ForeignTagPreservation(c);
        t5CorruptionRejectedSafely(c);
        t6DeterministicOrdering(c);
        return c;
    }

    private ComponentRegistry createTestRegistry() {
        return ComponentRegistry.builder()
                .register(ComponentId.of("test:count"), ComponentCodec.INT, 0)
                .register(ComponentId.of("test:name"), ComponentCodec.STRING, "")
                .register(ComponentId.of("test:active"), ComponentCodec.BOOLEAN, false)
                .register(ComponentId.of("test:weight"), ComponentCodec.DOUBLE, 0.0)
                .build();
    }

    private void t1RoundTripIsomorphism(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = createTestRegistry();

        ComponentId countId = ComponentId.of("test:count");
        ComponentId nameId = ComponentId.of("test:name");
        ComponentId activeId = ComponentId.of("test:active");

        CarrierSnapshot snapshot = CarrierSnapshot.empty()
                .with(countId, 42)
                .with(nameId, "The Fool")
                .with(activeId, true);

        TagNode.TagCompound nbt = translator.toLegacyNbt(snapshot, registry);
        CarrierSnapshot recovered = translator.fromLegacyNbt(nbt, registry);

        c.equal(snapshot, recovered, "T1:round-trip-isomorphism");

        // Seeded property check
        Seeded r = new Seeded(0xCA70L);
        for (int step = 0; step < 50; step++) {
            int countVal = r.nextInt(1000) + 1; // non-zero
            String nameVal = "card-" + r.nextInt(100);
            boolean activeVal = r.nextBoolean();

            CarrierSnapshot seedSnapshot = CarrierSnapshot.empty()
                    .with(countId, countVal)
                    .with(nameId, nameVal)
                    .with(activeId, activeVal)
                    .normalized(registry);

            TagNode.TagCompound seedNbt = translator.toLegacyNbt(seedSnapshot, registry);
            CarrierSnapshot seedRecovered = translator.fromLegacyNbt(seedNbt, registry);
            c.equal(seedSnapshot, seedRecovered, "T1:round-trip-isomorphism");
        }
    }

    private void t2DefaultElision(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = createTestRegistry();

        ComponentId countId = ComponentId.of("test:count");
        ComponentId nameId = ComponentId.of("test:name");

        // Snapshot holding strictly default values
        CarrierSnapshot defaultSnapshot = CarrierSnapshot.empty()
                .with(countId, 0)
                .with(nameId, "");

        TagNode.TagCompound nbt = translator.toLegacyNbt(defaultSnapshot, registry);

        // Law T2: Tag must be completely empty / omit fenced compound when all values are defaults
        c.that(!nbt.has(RosettaTranslator.FENCED_NAMESPACE), "T2:default-elision");
        c.that(nbt.isEmpty(), "T2:default-elision");
    }

    private void t3NamespaceFencing(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = createTestRegistry();

        ComponentId countId = ComponentId.of("test:count");
        CarrierSnapshot snapshot = CarrierSnapshot.empty().with(countId, 7);

        TagNode.TagCompound nbt = translator.toLegacyNbt(snapshot, registry);

        // Law T3: All components must be fenced strictly inside mycelium$c
        c.that(nbt.has(RosettaTranslator.FENCED_NAMESPACE), "T3:namespace-fencing");
        c.that(!nbt.has("test:count"), "T3:namespace-fencing");

        for (String rootKey : nbt.entries().keySet()) {
            c.that(rootKey.equals(RosettaTranslator.FENCED_NAMESPACE), "T3:namespace-fencing");
        }
    }

    private void t4ForeignTagPreservation(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = createTestRegistry();

        ComponentId countId = ComponentId.of("test:count");
        CarrierSnapshot snapshot = CarrierSnapshot.empty()
                .with(countId, 13)
                .withUnmappedTag("future_mod:special_aura", TagNode.TagString.of("celestial_gold"));

        TagNode.TagCompound nbt = translator.toLegacyNbt(snapshot, registry);
        CarrierSnapshot recovered = translator.fromLegacyNbt(nbt, registry);

        c.equal(snapshot, recovered, "T4:foreign-tag-preservation");
        c.equal(TagNode.TagString.of("celestial_gold"), recovered.unmappedTags().get("future_mod:special_aura"), "T4:foreign-tag-preservation");
    }

    private void t5CorruptionRejectedSafely(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = createTestRegistry();

        // Craft a corrupted NBT where test:count is a TagString instead of TagInt
        Map<String, TagNode> badFencedEntries = new TreeMap<>();
        badFencedEntries.put("test:count", TagNode.TagString.of("not_an_int"));
        TagNode.TagCompound badFenced = new TagNode.TagCompound(badFencedEntries);

        TagNode.TagCompound root = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, badFenced);

        try {
            CarrierSnapshot result = translator.fromLegacyNbt(root, registry);
            // Must not throw unchecked exception and must fall back to default
            c.that(!result.has(ComponentId.of("test:count")), "T5:corruption-rejected-safely");
        } catch (RuntimeException e) {
            c.fail("T5:corruption-rejected-safely");
        }
    }

    private void t6DeterministicOrdering(Check c) {
        RosettaTranslator translator = translators.get();
        ComponentRegistry registry = ComponentRegistry.builder()
                .register(ComponentId.of("z:last"), ComponentCodec.INT, 0)
                .register(ComponentId.of("a:first"), ComponentCodec.INT, 0)
                .register(ComponentId.of("m:middle"), ComponentCodec.INT, 0)
                .build();

        CarrierSnapshot snapshot = CarrierSnapshot.empty()
                .with(ComponentId.of("z:last"), 3)
                .with(ComponentId.of("a:first"), 1)
                .with(ComponentId.of("m:middle"), 2);

        TagNode.TagCompound nbt = translator.toLegacyNbt(snapshot, registry);
        TagNode.TagCompound fenced = nbt.getCompound(RosettaTranslator.FENCED_NAMESPACE).orElse(TagNode.TagCompound.empty());

        List<String> keys = new ArrayList<>(fenced.entries().keySet());
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);

        c.equal(sorted, keys, "T6:deterministic-ordering");
    }
}
