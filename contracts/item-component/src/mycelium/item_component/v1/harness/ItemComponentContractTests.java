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
        RosettaTranslator candidate = translators.get();

        // Run model invariant probe suites on reference candidate
        if (candidate.getClass().getSimpleName().equals("ReferenceTranslator") || candidate instanceof StandardRosettaTranslator) {
            Step1AdversarialProbes.runAll();
            Step2AdversarialProbes.runAll(candidate);
            Step3AdversarialProbes.runAll(candidate);
            Step4AdversarialProbes.runAll(candidate);
            Step5AdversarialProbes.runAll(candidate);
        }

        t1RoundTripIsomorphism(c);
        t2DefaultElision(c);
        t3NamespaceFencing(c);
        t4ForeignTagPreservation(c);
        t5CorruptionRejectedSafely(c);
        t6DeterministicOrdering(c);
        t7RealWorldModVectors(c);
        t8AdversarialEdgeCases(c);
        t9IntegratedCompilerLifecycle(c);
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

        // Law T3: All components must be fenced strictly inside mycelium$c (zero root pollution)
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

    private void t7RealWorldModVectors(Check c) {
        RosettaTranslator translator = translators.get();

        // 1. Farmer's Delight Real Mod Vector
        ComponentId mealId = ComponentId.of("farmersdelight:meal");
        ComponentId containerId = ComponentId.of("farmersdelight:container");
        ComponentId cookTimeId = ComponentId.of("farmersdelight:cooking_time_length");
        ComponentId skilletFlippedId = ComponentId.of("farmersdelight:skillet_flipped");
        ComponentId skilletTimeId = ComponentId.of("farmersdelight:skillet_flip_timestamp");

        ComponentRegistry fdRegistry = ComponentRegistry.builder()
                .register(mealId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(containerId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(cookTimeId, ComponentCodec.INT, 0)
                .register(skilletFlippedId, ComponentCodec.BOOLEAN, false)
                .register(skilletTimeId, ComponentCodec.LONG, 0L)
                .build();

        TagNode.TagCompound mealData = TagNode.TagCompound.empty()
                .with("id", TagNode.TagString.of("farmersdelight:noodle_soup"))
                .with("count", TagNode.TagInt.of(1));
        TagNode.TagCompound containerData = TagNode.TagCompound.empty()
                .with("id", TagNode.TagString.of("minecraft:bowl"))
                .with("count", TagNode.TagInt.of(1));

        CarrierSnapshot fdSnapshot = CarrierSnapshot.empty()
                .with(mealId, mealData)
                .with(containerId, containerData)
                .with(cookTimeId, 200)
                .with(skilletFlippedId, true)
                .with(skilletTimeId, 1728639200000L);

        TagNode.TagCompound fdNbt = translator.toLegacyNbt(fdSnapshot, fdRegistry);
        CarrierSnapshot fdRecovered = translator.fromLegacyNbt(fdNbt, fdRegistry);
        c.equal(fdSnapshot, fdRecovered, "T1:round-trip-isomorphism");

        // 2. Iron's Spells 'n Spellbooks Real Mod Vector
        ComponentId spellsId = ComponentId.of("irons_spellbooks:spell_container");
        ComponentId castingId = ComponentId.of("irons_spellbooks:casting_implement");
        ComponentId variantId = ComponentId.of("irons_spellbooks:clothing_variant");

        ComponentRegistry isbRegistry = ComponentRegistry.builder()
                .register(spellsId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(castingId, ComponentCodec.UNIT, null)
                .register(variantId, ComponentCodec.STRING, "")
                .build();

        TagNode.TagCompound fireboltSpell = TagNode.TagCompound.empty()
                .with("id", TagNode.TagString.of("irons_spellbooks:firebolt"))
                .with("level", TagNode.TagInt.of(3))
                .with("locked", TagNode.TagByte.of((byte) 0))
                .with("slot", TagNode.TagInt.of(0));

        TagNode.TagCompound healSpell = TagNode.TagCompound.empty()
                .with("id", TagNode.TagString.of("irons_spellbooks:heal"))
                .with("level", TagNode.TagInt.of(1))
                .with("locked", TagNode.TagByte.of((byte) 1))
                .with("slot", TagNode.TagInt.of(1));

        TagNode.TagList spellList = TagNode.TagList.of(fireboltSpell, healSpell);

        TagNode.TagCompound spellContainer = TagNode.TagCompound.empty()
                .with("maxSpells", TagNode.TagInt.of(5))
                .with("mustEquip", TagNode.TagByte.of((byte) 1))
                .with("spellWheel", TagNode.TagByte.of((byte) 1))
                .with("spells", spellList);

        CarrierSnapshot isbSnapshot = CarrierSnapshot.empty()
                .with(spellsId, spellContainer)
                .with(castingId, Unit.INSTANCE)
                .with(variantId, "cryomancer");

        TagNode.TagCompound isbNbt = translator.toLegacyNbt(isbSnapshot, isbRegistry);
        CarrierSnapshot isbRecovered = translator.fromLegacyNbt(isbNbt, isbRegistry);
        c.equal(isbSnapshot, isbRecovered, "T1:round-trip-isomorphism");
        ComponentId cardDataId = ComponentId.of("example:card_instance");
        ComponentId deckBoxId = ComponentId.of("example:deck_box_contents");
        ComponentId rackId = ComponentId.of("example:active_rack");

        ComponentRegistry cartoRegistry = ComponentRegistry.builder()
                .register(cardDataId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(deckBoxId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(rackId, ComponentCodec.INT_ARRAY, new int[0])
                .build();

        TagNode.TagCompound cardInstance = TagNode.TagCompound.empty()
                .with("card_id", TagNode.TagString.of("example:the_fool"))
                .with("condition", TagNode.TagString.of("MINT"))
                .with("edition", TagNode.TagString.of("GILDED_FOIL"))
                .with("is_foil", TagNode.TagByte.of((byte) 1))
                .with("bond_xp", TagNode.TagInt.of(250))
                .with("play_count", TagNode.TagInt.of(42))
                .with("inscription", TagNode.TagString.of("Drawn under a waxing crescent moon"));

        int[] rackIndices = new int[]{ 0, 3, 7, 12, 19, 21, 24, 30, 42 };

        CarrierSnapshot cartoSnapshot = CarrierSnapshot.empty()
                .with(cardDataId, cardInstance)
                .with(rackId, rackIndices)
                .withRemoval(ComponentId.of("example:deprecated_tag"));

        TagNode.TagCompound cartoNbt = translator.toLegacyNbt(cartoSnapshot, cartoRegistry);
        CarrierSnapshot cartoRecovered = translator.fromLegacyNbt(cartoNbt, cartoRegistry);

        c.equal(cartoSnapshot, cartoRecovered, "T1:round-trip-isomorphism");

        // 4. Anti-Data Loss Quarantine Vector
        TagNode.TagCompound corruptFenced = TagNode.TagCompound.empty()
                .with("test:count", TagNode.TagString.of("corrupt_string_value"));
        TagNode.TagCompound corruptRoot = TagNode.TagCompound.empty()
                .with(RosettaTranslator.FENCED_NAMESPACE, corruptFenced);

        ComponentRegistry simpleRegistry = ComponentRegistry.builder()
                .register(ComponentId.of("test:count"), ComponentCodec.INT, 0)
                .build();

        CarrierSnapshot corruptRecovered;
        try {
            corruptRecovered = translator.fromLegacyNbt(corruptRoot, simpleRegistry);
            // Domain level sees default
            c.that(!corruptRecovered.has(ComponentId.of("test:count")), "T5:corruption-rejected-safely");
        } catch (RuntimeException e) {
            c.fail("T5:corruption-rejected-safely");
            return;
        }

        // Re-saving must preserve quarantined raw tag without data loss
        TagNode.TagCompound reSaved = translator.toLegacyNbt(corruptRecovered, simpleRegistry);
        Optional<TagNode.TagCompound> reFenced = reSaved.getCompound(RosettaTranslator.FENCED_NAMESPACE);
        c.that(reFenced.isPresent(), "T4:foreign-tag-preservation");
        if (reFenced.isPresent()) {
            c.equal(TagNode.TagString.of("corrupt_string_value"), reFenced.get().get("test:count"), "T4:foreign-tag-preservation");
        }
    }

    private void t8AdversarialEdgeCases(Check c) {
        RosettaTranslator translator = translators.get();

        // 1. Ars Nouveau Spell Caster Real Vector
        ComponentId anSpellCasterId = ComponentId.of("ars_nouveau:spell_caster");
        ComponentId anUnbreakingId = ComponentId.of("ars_nouveau:unbreaking");
        ComponentRegistry anRegistry = ComponentRegistry.builder()
                .register(anSpellCasterId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .register(anUnbreakingId, ComponentCodec.BOOLEAN, false)
                .build();

        TagNode.TagCompound slot0 = TagNode.TagCompound.empty()
                .with("spell_name", TagNode.TagString.of("Lightning Storm"))
                .with("glyphs", TagNode.TagList.of(
                        TagNode.TagString.of("ars_nouveau:glyph_projectile"),
                        TagNode.TagString.of("ars_nouveau:glyph_amplify"),
                        TagNode.TagString.of("ars_nouveau:glyph_lightning"),
                        TagNode.TagString.of("ars_nouveau:glyph_aoe")
                ));

        TagNode.TagCompound slot1 = TagNode.TagCompound.empty()
                .with("spell_name", TagNode.TagString.of("Blink"))
                .with("glyphs", TagNode.TagList.of(
                        TagNode.TagString.of("ars_nouveau:glyph_blink")
                ));

        TagNode.TagCompound spellSlots = TagNode.TagCompound.empty()
                .with("slot_0", slot0)
                .with("slot_1", slot1);

        TagNode.TagCompound spellCasterData = TagNode.TagCompound.empty()
                .with("current_slot", TagNode.TagInt.of(0))
                .with("flavor_text", TagNode.TagString.of("Spellbook of the Archmage"))
                .with("is_hidden", TagNode.TagByte.of((byte) 0))
                .with("max_slots", TagNode.TagInt.of(10))
                .with("sound", TagNode.TagString.of("ars_nouveau:fire_cast"))
                .with("spell_slots", spellSlots);

        CarrierSnapshot anSnapshot = CarrierSnapshot.empty()
                .with(anSpellCasterId, spellCasterData)
                .with(anUnbreakingId, true);

        TagNode.TagCompound anNbt = translator.toLegacyNbt(anSnapshot, anRegistry);
        CarrierSnapshot anRecovered = translator.fromLegacyNbt(anNbt, anRegistry);
        c.equal(anSnapshot, anRecovered, "T1:round-trip-isomorphism");

        // 2. Deep 5-level nesting round-trip
        ComponentId deepId = ComponentId.of("deep:tree");
        ComponentRegistry deepRegistry = ComponentRegistry.builder()
                .register(deepId, ComponentCodec.COMPOUND, TagNode.TagCompound.empty())
                .build();

        TagNode.TagCompound level5 = TagNode.TagCompound.empty()
                .with("leaf", TagNode.TagString.of("root_cause"))
                .with("leaf_bytes", new TagNode.TagByteArray(new byte[]{ 0x1, 0x2, 0x3 }))
                .with("leaf_longs", new TagNode.TagLongArray(new long[]{ 1000L, 2000L }));
        TagNode.TagCompound level4 = TagNode.TagCompound.empty().with("l5", level5);
        TagNode.TagCompound level3 = TagNode.TagCompound.empty().with("l4", level4);
        TagNode.TagCompound level2 = TagNode.TagCompound.empty().with("l3", level3);
        TagNode.TagCompound level1 = TagNode.TagCompound.empty().with("l2", level2);

        CarrierSnapshot deepSnapshot = CarrierSnapshot.empty().with(deepId, level1);
        TagNode.TagCompound deepNbt = translator.toLegacyNbt(deepSnapshot, deepRegistry);
        CarrierSnapshot deepRecovered = translator.fromLegacyNbt(deepNbt, deepRegistry);
        c.equal(deepSnapshot, deepRecovered, "T1:round-trip-isomorphism");

        // 3. Special Strings (runes, formatting codes, quotes, zero-length arrays)
        ComponentId runesId = ComponentId.of("example:rune_inscription");
        ComponentId byteArrayId = ComponentId.of("example:byte_array");
        ComponentRegistry specialRegistry = ComponentRegistry.builder()
                .register(runesId, ComponentCodec.STRING, "")
                .register(byteArrayId, ComponentCodec.BYTE_ARRAY, new byte[0])
                .build();

        CarrierSnapshot specialSnapshot = CarrierSnapshot.empty()
                .with(runesId, "ᚠᚢᚦᚨᚱᚲ §6Gilded Foil§r \"The Fool's Journey\" \n\t\r")
                .with(byteArrayId, new byte[]{ 0x1, 0x2, 0x3 });

        TagNode.TagCompound specialNbt = translator.toLegacyNbt(specialSnapshot, specialRegistry);
        CarrierSnapshot specialRecovered = translator.fromLegacyNbt(specialNbt, specialRegistry);
        c.equal(specialSnapshot, specialRecovered, "T1:round-trip-isomorphism");

        // Verify Law T2: default array elision
        CarrierSnapshot defaultArraySnapshot = CarrierSnapshot.empty().with(byteArrayId, new byte[0]);
        TagNode.TagCompound defaultNbt = translator.toLegacyNbt(defaultArraySnapshot, specialRegistry);
        c.that(defaultNbt.isEmpty(), "T2:default-elision");

        // 4. Snapshot Mutation Interplay (with, withRemoval, without)
        ComponentId idA = ComponentId.of("test:comp_a");
        ComponentId idB = ComponentId.of("test:comp_b");
        CarrierSnapshot s0 = CarrierSnapshot.empty().withRemoval(idA).with(idB, 10);
        c.that(s0.isRemoved(idA), "T1:round-trip-isomorphism");
        c.that(!s0.has(idA), "T1:round-trip-isomorphism");

        // Setting a previously removed component un-marks removal
        CarrierSnapshot s1 = s0.with(idA, 20);
        c.that(!s1.isRemoved(idA), "T1:round-trip-isomorphism");
        c.that(s1.has(idA), "T1:round-trip-isomorphism");

        // Removing a previously set component clears value and marks removal
        CarrierSnapshot s2 = s1.withRemoval(idB);
        c.that(s2.isRemoved(idB), "T1:round-trip-isomorphism");
        c.that(!s2.has(idB), "T1:round-trip-isomorphism");

        // Calling without clears both value and removal
        CarrierSnapshot s3 = s2.without(idB);
        c.that(!s3.isRemoved(idB), "T1:round-trip-isomorphism");
        c.that(!s3.has(idB), "T1:round-trip-isomorphism");
    }

    private void t9IntegratedCompilerLifecycle(Check c) {
        RosettaTranslator translator = translators.get();

        ComponentId nameId = ComponentId.of("minecraft:custom_name");
        ComponentId levelId = ComponentId.of("example:level");
        ComponentId secretId = ComponentId.of("server_internal:secret");
        ComponentId deprecatedId = ComponentId.of("example:deprecated_power");

        FieldAwareCodec<Integer> levelLens = FieldAwareCodec.ofCompoundField("level", ComponentCodec.INT);

        ComponentDefinition<String> nameDef = new ComponentDefinition<>(nameId, ComponentCodec.STRING, "");
        ComponentDefinition<Integer> levelDef = new ComponentDefinition<>(levelId, ComponentCodec.INT, 1).withFieldAwareCodec(levelLens);
        ComponentDefinition<String> secretDef = new ComponentDefinition<>(secretId, ComponentCodec.STRING, "").withNetworkSynced(false);
        ComponentDefinition<Integer> depDef = new ComponentDefinition<>(deprecatedId, ComponentCodec.INT, 10);

        ComponentRegistry registry = ComponentRegistry.builder()
                .register(nameDef)
                .register(levelDef)
                .register(secretDef)
                .register(depDef)
                .build();

        ItemPrototype prototype = ItemPrototype.builder()
                .withBaseComponent(levelId, 1)
                .withBaseComponent(depDef, 10)
                .build();

        NativeProjectionRecipe nameRecipe = NativeProjectionRecipe.displayName();

        TranslationPlan plan = TranslationPlan.builder()
                .withRegistry(registry)
                .withPrototype(prototype)
                .withNativeRecipe(nameRecipe)
                .build();

        // 1. Initial legacy NBT from game world
        TagNode.TagCompound rawLevelCompound = TagNode.TagCompound.empty()
                .with("level", TagNode.TagInt.of(5))
                .with("future_aspect", TagNode.TagString.of("starlight_void"));

        TagNode.TagCompound fenced = TagNode.TagCompound.empty()
                .with("example:level", rawLevelCompound)
                .with("server_internal:secret", TagNode.TagString.of("key_123"))
                .with("unknown:foreign_data", TagNode.TagInt.of(42))
                .with("!removed", TagNode.TagList.of(TagNode.TagString.of("example:deprecated_power")));

        TagNode.TagCompound displayCompound = TagNode.TagCompound.empty()
                .with("Name", TagNode.TagString.of("Original Blade"))
                .with("Lore", TagNode.TagList.of(TagNode.TagString.of("Ancient artifact")));

        TagNode.TagCompound root = TagNode.TagCompound.empty()
                .with("display", displayCompound)
                .with(RosettaTranslator.FENCED_NAMESPACE, fenced);

        // 2. Decode from legacy NBT
        CarrierSnapshot s0 = plan.fromLegacyNbt(root);
        c.that(s0.has(levelId) && Objects.equals(s0.get(levelId), 5), "T1:round-trip-isomorphism");
        c.that(s0.isRemoved(deprecatedId), "T1:round-trip-isomorphism");
        c.that(s0.unmappedTags().containsKey("unknown:foreign_data"), "T4:foreign-tag-preservation");

        // 3. Typed edit: level from 5 to 6
        CarrierSnapshot s1 = s0.with(levelId, 6);
        c.that(Objects.equals(s1.get(levelId), 6), "T1:round-trip-isomorphism");

        // 4. Native edit: player renames item at anvil
        TagNode.TagCompound n0 = plan.toLegacyNbt(s1);
        TagNode.TagCompound n0WithDisplay = nameRecipe.project(s1, n0.with("display", displayCompound));
        TagNode.TagCompound n1 = n0WithDisplay.with("display", TagNode.TagCompound.empty()
                .with("Name", TagNode.TagString.of("Forged Starlight"))
                .with("Lore", TagNode.TagList.of(TagNode.TagString.of("Ancient artifact"))));

        CarrierSnapshot s2 = plan.restoreEdited(s1, n0WithDisplay, n1);
        c.that(Objects.equals(s2.getString(nameId).orElse(""), "Forged Starlight"), "T1:round-trip-isomorphism");

        // 5. Network projection: server_internal:secret is stripped
        CarrierSnapshot netView = ChannelPolicy.NETWORK.project(s2, registry, prototype);
        c.that(!netView.has(secretId), "T1:round-trip-isomorphism");
        c.that(netView.has(levelId), "T1:round-trip-isomorphism");

        // 6. Serialize back to legacy NBT
        TagNode.TagCompound reSaved = plan.toLegacyNbt(s2);
        c.that(reSaved.has(RosettaTranslator.FENCED_NAMESPACE), "T3:namespace-fencing");

        // 7. Reload and verify round-trip preservation
        CarrierSnapshot restored = plan.fromLegacyNbt(reSaved);
        c.equal(s2.components(), restored.components(), "T1:round-trip-isomorphism");
        c.that(restored.isRemoved(deprecatedId), "T1:round-trip-isomorphism");
        c.that(restored.unmappedTags().containsKey("unknown:foreign_data"), "T4:foreign-tag-preservation");
    }
}
