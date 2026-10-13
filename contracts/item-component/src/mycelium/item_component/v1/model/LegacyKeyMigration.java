package mycelium.item_component.v1.model;

import java.util.*;

/**
 * A declared table of a mod's old ad-hoc keys and the components they become. Every mod adopting Rosetta has item state
 * it once stored under keys of its own; this says, once, where each such key goes. {@link FencedCarrier} applies it on
 * read (the old values appear as components, T16), strips the old keys on the next write (T17), reads an
 * already-migrated root to the same snapshot (T18), quarantines an old value it cannot read instead of dropping it
 * (T19), and touches no key the table does not name (T20).
 *
 * <p>A rule has one or more old keys and one target component. A key is a root key ({@code "somnium_rig"}) or a key
 * inside an old compound ({@code Key.of("somnium", "rig")}). When one key becomes a whole component, give the old shape
 * as a {@link ComponentCodec}; when several keys are the fields of one component (or a key is only part of a value), give a
 * {@link Reader} that builds the value from those present. {@link Builder#drop} retires a key whose value is read nowhere.
 *
 * <p>If the target is already carried, explicitly removed, or the stack's fenced entry for it is stored but unreadable,
 * the fenced state wins and the old keys are not migrated; in the first two the stale old keys are stripped on write, in
 * the quarantined case they are kept (their value may be the only good copy).
 */
public final class LegacyKeyMigration {
    private static final LegacyKeyMigration NONE = new LegacyKeyMigration(List.of(), List.of());

    /** Prefix of the fenced key an unreadable old value is quarantined under: {@code "!legacy:" + key}. */
    public static final String QUARANTINE_PREFIX = "!legacy:";

    private final List<ComponentRule<?>> rules;
    private final List<Key> drops;

    private LegacyKeyMigration(List<ComponentRule<?>> rules, List<Key> drops) {
        this.rules = List.copyOf(rules);
        this.drops = List.copyOf(drops);
    }

    /** The empty table: nothing to migrate. */
    public static LegacyKeyMigration none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<ComponentRule<?>> rules() {
        return rules;
    }

    public List<Key> drops() {
        return drops;
    }

    public boolean isEmpty() {
        return rules.isEmpty() && drops.isEmpty();
    }

    // ---------------------------------------------------------------------------------------------------- vocabulary

    /** Where an old value lives in the root: a root key, or a path through old compounds. */
    public record Key(List<String> path) {
        public Key {
            Objects.requireNonNull(path, "path");
            if (path.isEmpty()) {
                throw new IllegalArgumentException("A legacy key has at least one segment");
            }
            path = List.copyOf(path);
            for (String segment : path) {
                if (segment == null || segment.isEmpty()) {
                    throw new IllegalArgumentException("A legacy key has no empty segment: " + path);
                }
            }
            if (path.get(0).equals(RosettaTranslator.FENCED_NAMESPACE) || path.get(0).startsWith("!")) {
                throw new IllegalArgumentException("A legacy key cannot be the fence or a control key: " + path);
            }
        }

        public static Key of(String... path) {
            return new Key(Arrays.asList(path));
        }

        @Override
        public String toString() {
            return String.join("/", path);
        }

        /** The value at this key in {@code root}, or null if any segment is missing or not a compound. */
        TagNode find(TagNode.TagCompound root) {
            TagNode.TagCompound at = root;
            for (int i = 0; i < path.size() - 1; i++) {
                if (!(at.get(path.get(i)) instanceof TagNode.TagCompound next)) {
                    return null;
                }
                at = next;
            }
            return at.get(path.get(path.size() - 1));
        }

        /** {@code root} without this key; compounds the removal leaves empty are removed too. */
        TagNode.TagCompound remove(TagNode.TagCompound root) {
            return remove(root, 0);
        }

        private TagNode.TagCompound remove(TagNode.TagCompound at, int depth) {
            String segment = path.get(depth);
            if (!at.has(segment)) {
                return at;
            }
            if (depth == path.size() - 1) {
                return at.without(segment);
            }
            if (!(at.get(segment) instanceof TagNode.TagCompound child)) {
                return at;
            }
            TagNode.TagCompound next = remove(child, depth + 1);
            if (next == child) {
                return at;
            }
            return next.isEmpty() ? at.without(segment) : at.with(segment, next);
        }
    }

    /** The old values found in the root for one rule, by key. Reading one in the wrong shape is a {@link CodecException}. */
    public static final class Values {
        private final Map<Key, TagNode> present;

        Values(Map<Key, TagNode> present) {
            this.present = present;
        }

        public boolean has(Key key) {
            return present.containsKey(key);
        }

        public boolean has(String rootKey) {
            return has(Key.of(rootKey));
        }

        public Optional<TagNode> node(Key key) {
            return Optional.ofNullable(present.get(key));
        }

        public Optional<TagNode> node(String rootKey) {
            return node(Key.of(rootKey));
        }

        /** The string at the key, or {@code fallback} if the key is not present. */
        public String string(Key key, String fallback) throws CodecException {
            TagNode n = present.get(key);
            if (n == null) {
                return fallback;
            }
            if (n instanceof TagNode.TagString s) {
                return s.value();
            }
            throw new CodecException("Expected a string at old key " + key + ", got " + n.getClass().getSimpleName());
        }

        public String string(String rootKey, String fallback) throws CodecException {
            return string(Key.of(rootKey), fallback);
        }

        /** The integral number (any width) at the key, or {@code fallback} if the key is not present. */
        public long longValue(Key key, long fallback) throws CodecException {
            TagNode n = present.get(key);
            if (n == null) {
                return fallback;
            }
            if (n instanceof TagNode.TagByte b) return b.value();
            if (n instanceof TagNode.TagShort s) return s.value();
            if (n instanceof TagNode.TagInt i) return i.value();
            if (n instanceof TagNode.TagLong l) return l.value();
            throw new CodecException("Expected an integer at old key " + key + ", got " + n.getClass().getSimpleName());
        }

        public long longValue(String rootKey, long fallback) throws CodecException {
            return longValue(Key.of(rootKey), fallback);
        }

        /** Like {@link #longValue} but it must fit an {@code int}. */
        public int integer(Key key, int fallback) throws CodecException {
            long v = longValue(key, fallback);
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
                throw new CodecException("Integer at old key " + key + " out of range: " + v);
            }
            return (int) v;
        }

        public int integer(String rootKey, int fallback) throws CodecException {
            return integer(Key.of(rootKey), fallback);
        }

        /** Whether the key is present as a mark: any value counts (an old flag was often any byte). */
        public boolean mark(String rootKey) {
            return has(rootKey);
        }
    }

    /** Builds a component's value from the old values present for its rule (at least one is). */
    @FunctionalInterface
    public interface Reader<T> {
        T read(Values present) throws CodecException;
    }

    /** One rule: these old keys become this component. */
    public record ComponentRule<T>(List<Key> keys, ComponentDefinition<T> target, Reader<T> reader) {
        public ComponentRule {
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(reader, "reader");
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("A rule has at least one old key");
            }
        }

        /** The old values of this rule present in {@code root}. */
        public Map<Key, TagNode> found(TagNode.TagCompound root) {
            Map<Key, TagNode> found = new LinkedHashMap<>();
            for (Key key : keys) {
                TagNode n = key.find(root);
                if (n != null) {
                    found.put(key, n);
                }
            }
            return found;
        }

        /** The value the rule gives for these old values; a {@link CodecException} when they cannot be read. */
        public T apply(Map<Key, TagNode> present) throws CodecException {
            T value = reader.read(new Values(present));
            if (value == null) {
                throw new CodecException("The reader for " + target.id() + " returned null");
            }
            target.codec().validate(value);
            return value;
        }
    }

    // ---------------------------------------------------------------------------------------------------- building

    public static final class Builder {
        private final List<ComponentRule<?>> rules = new ArrayList<>();
        private final List<Key> drops = new ArrayList<>();

        /** The root key {@code oldKey} holds the whole value of {@code target}, in the shape {@code oldShape} reads. */
        public <T> Builder key(String oldKey, ComponentDefinition<T> target, ComponentCodec<T> oldShape) {
            return key(Key.of(oldKey), target, oldShape);
        }

        public <T> Builder key(Key oldKey, ComponentDefinition<T> target, ComponentCodec<T> oldShape) {
            Objects.requireNonNull(oldShape, "oldShape");
            return keys(List.of(oldKey), target, present -> oldShape.decode(present.node(oldKey).orElseThrow()));
        }

        /** The root key {@code oldKey}, whatever its value, is the presence mark {@code target}. */
        public Builder mark(String oldKey, ComponentDefinition<Unit> target) {
            return keys(List.of(Key.of(oldKey)), target, present -> Unit.INSTANCE);
        }

        /**
         * Several old keys (or one key that is only part of a value) become {@code target}; {@code reader} builds the value
         * from the keys present, filling the ones that are missing as the old code did.
         */
        public <T> Builder keys(List<Key> oldKeys, ComponentDefinition<T> target, Reader<T> reader) {
            rules.add(new ComponentRule<>(oldKeys, target, reader));
            return this;
        }

        /** A retired key whose value nothing reads: stripped on write, read nowhere. */
        public Builder drop(String oldKey) {
            return drop(Key.of(oldKey));
        }

        public Builder drop(Key oldKey) {
            drops.add(Objects.requireNonNull(oldKey, "oldKey"));
            return this;
        }

        public LegacyKeyMigration build() {
            Set<ComponentId> targets = new HashSet<>();
            Set<Key> keys = new HashSet<>();
            for (ComponentRule<?> rule : rules) {
                if (!targets.add(rule.target().id())) {
                    throw new IllegalStateException("Two rules migrate into " + rule.target().id());
                }
                for (Key key : rule.keys()) {
                    claim(keys, key);
                }
            }
            for (Key key : drops) {
                claim(keys, key);
            }
            return new LegacyKeyMigration(rules, drops);
        }

        private static void claim(Set<Key> keys, Key key) {
            if (!keys.add(key)) {
                throw new IllegalStateException("The old key " + key + " is named twice");
            }
        }
    }
}
