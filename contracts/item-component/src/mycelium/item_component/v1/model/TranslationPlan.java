package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Compiled translation plan coordinating component schemas, source and target prototypes,
 * field-aware lenses, channel projections, native projection recipes, and recovery storage
 * into a single unified execution pipeline.
 */
public final class TranslationPlan {
    private final ComponentRegistry registry;
    private final ItemPrototype sourcePrototype;
    private final ItemPrototype targetPrototype;
    private final ChannelPolicy channelPolicy;
    private final List<NativeProjectionRecipe> nativeRecipes;

    public TranslationPlan(
            ComponentRegistry registry,
            ItemPrototype sourcePrototype,
            ItemPrototype targetPrototype,
            ChannelPolicy channelPolicy,
            List<NativeProjectionRecipe> nativeRecipes
    ) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.sourcePrototype = Objects.requireNonNull(sourcePrototype, "sourcePrototype");
        this.targetPrototype = Objects.requireNonNull(targetPrototype, "targetPrototype");
        this.channelPolicy = Objects.requireNonNull(channelPolicy, "channelPolicy");
        this.nativeRecipes = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(nativeRecipes, "nativeRecipes")));
    }

    public ComponentRegistry registry() {
        return registry;
    }

    public ItemPrototype sourcePrototype() {
        return sourcePrototype;
    }

    public ItemPrototype targetPrototype() {
        return targetPrototype;
    }

    public ChannelPolicy channelPolicy() {
        return channelPolicy;
    }

    public List<NativeProjectionRecipe> nativeRecipes() {
        return nativeRecipes;
    }

    public TagNode.TagCompound toLegacyNbt(CarrierSnapshot snapshot) {
        return StandardRosettaTranslator.INSTANCE.toLegacyNbt(snapshot, registry, targetPrototype);
    }

    public CarrierSnapshot fromLegacyNbt(TagNode.TagCompound nbt) {
        return StandardRosettaTranslator.INSTANCE.fromLegacyNbt(nbt, registry, sourcePrototype);
    }

    public CarrierSnapshot canonicalize(CarrierSnapshot snapshot) {
        return snapshot.canonicalize(targetPrototype, registry);
    }

    public CarrierSnapshot restoreEdited(CarrierSnapshot s0, TagNode.TagCompound n0, TagNode.TagCompound n1) {
        CarrierSnapshot current = s0;
        for (NativeProjectionRecipe recipe : nativeRecipes) {
            current = recipe.restoreEdited(current, n0, n1, registry);
        }
        return current;
    }

    public CarrierSnapshot project(CarrierSnapshot snapshot) {
        return channelPolicy.project(snapshot, registry, targetPrototype);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private ComponentRegistry registry = ComponentRegistry.empty();
        private ItemPrototype sourcePrototype = ItemPrototype.empty();
        private ItemPrototype targetPrototype = ItemPrototype.empty();
        private ChannelPolicy channelPolicy = ChannelPolicy.PERSISTENT;
        private final List<NativeProjectionRecipe> nativeRecipes = new ArrayList<>();

        public Builder withRegistry(ComponentRegistry registry) {
            this.registry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        public Builder withPrototype(ItemPrototype prototype) {
            this.sourcePrototype = Objects.requireNonNull(prototype, "prototype");
            this.targetPrototype = prototype;
            return this;
        }

        public Builder withSourcePrototype(ItemPrototype prototype) {
            this.sourcePrototype = Objects.requireNonNull(prototype, "prototype");
            return this;
        }

        public Builder withTargetPrototype(ItemPrototype prototype) {
            this.targetPrototype = Objects.requireNonNull(prototype, "prototype");
            return this;
        }

        public Builder withChannelPolicy(ChannelPolicy policy) {
            this.channelPolicy = Objects.requireNonNull(policy, "channelPolicy");
            return this;
        }

        public Builder withNativeRecipe(NativeProjectionRecipe recipe) {
            this.nativeRecipes.add(Objects.requireNonNull(recipe, "recipe"));
            return this;
        }

        public TranslationPlan build() {
            return new TranslationPlan(registry, sourcePrototype, targetPrototype, channelPolicy, nativeRecipes);
        }
    }
}
