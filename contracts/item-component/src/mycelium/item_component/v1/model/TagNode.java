package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure algebraic data type representing NBT AST without Minecraft dependencies.
 * Sealed hierarchy ensures completeness across all NBT types.
 */
public sealed interface TagNode permits
        TagNode.TagByte,
        TagNode.TagShort,
        TagNode.TagInt,
        TagNode.TagLong,
        TagNode.TagFloat,
        TagNode.TagDouble,
        TagNode.TagByteArray,
        TagNode.TagString,
        TagNode.TagList,
        TagNode.TagCompound,
        TagNode.TagIntArray,
        TagNode.TagLongArray {

    byte typeId();

    record TagByte(byte value) implements TagNode {
        @Override public byte typeId() { return 1; }
        public static TagByte of(byte v) { return new TagByte(v); }
        public static TagByte ofBoolean(boolean b) { return new TagByte((byte) (b ? 1 : 0)); }
        @Override public String toString() { return "TagByte[value=" + ((int) value) + "]"; }
    }

    record TagShort(short value) implements TagNode {
        @Override public byte typeId() { return 2; }
        public static TagShort of(short v) { return new TagShort(v); }
        @Override public String toString() { return "TagShort[value=" + ((int) value) + "]"; }
    }

    record TagInt(int value) implements TagNode {
        @Override public byte typeId() { return 3; }
        public static TagInt of(int v) { return new TagInt(v); }
    }

    record TagLong(long value) implements TagNode {
        @Override public byte typeId() { return 4; }
        public static TagLong of(long v) { return new TagLong(v); }
    }

    record TagFloat(float value) implements TagNode {
        @Override public byte typeId() { return 5; }
        public static TagFloat of(float v) { return new TagFloat(v); }
    }

    record TagDouble(double value) implements TagNode {
        @Override public byte typeId() { return 6; }
        public static TagDouble of(double v) { return new TagDouble(v); }
    }

    record TagByteArray(byte[] value) implements TagNode {
        public TagByteArray {
            value = value == null ? new byte[0] : value.clone();
        }
        @Override public byte typeId() { return 7; }
        @Override public byte[] value() { return value.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof TagByteArray other && Arrays.equals(value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "TagByteArray[value=" + Arrays.toString(value) + "]"; }
    }

    record TagString(String value) implements TagNode {
        public TagString {
            Objects.requireNonNull(value, "value");
        }
        @Override public byte typeId() { return 8; }
        public static TagString of(String s) { return new TagString(s); }
    }

    record TagList(List<TagNode> elements) implements TagNode {
        public TagList {
            elements = elements == null ? List.of() : List.copyOf(elements);
            if (!elements.isEmpty()) {
                byte firstType = elements.get(0).typeId();
                for (int i = 1; i < elements.size(); i++) {
                    if (elements.get(i).typeId() != firstType) {
                        throw new IllegalArgumentException("TagList elements must have homogeneous typeId: expected "
                                + firstType + " but element " + i + " was " + elements.get(i).typeId());
                    }
                }
            }
        }
        @Override public byte typeId() { return 9; }
        public byte elementTypeId() { return elements.isEmpty() ? 0 : elements.get(0).typeId(); }
        public static TagList of(TagNode... items) { return new TagList(List.of(items)); }
        public static TagList of(List<TagNode> items) { return new TagList(items); }
        public int size() { return elements.size(); }
        public TagNode get(int index) { return elements.get(index); }
    }

    record TagCompound(Map<String, TagNode> entries) implements TagNode {
        public TagCompound {
            if (entries == null || entries.isEmpty()) {
                entries = Collections.emptyMap();
            } else {
                entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
            }
        }

        @Override public byte typeId() { return 10; }

        public static final TagCompound EMPTY = new TagCompound(Collections.emptyMap());

        public static TagCompound empty() { return EMPTY; }

        public boolean isEmpty() { return entries.isEmpty(); }
        public int size() { return entries.size(); }
        public boolean has(String key) { return entries.containsKey(key); }
        public TagNode get(String key) { return entries.get(key); }

        public Optional<TagNode> getOptional(String key) {
            return Optional.ofNullable(entries.get(key));
        }

        public Optional<String> getString(String key) {
            return getOptional(key).filter(n -> n instanceof TagString).map(n -> ((TagString) n).value());
        }

        public Optional<Integer> getInt(String key) {
            return getOptional(key).filter(n -> n instanceof TagInt).map(n -> ((TagInt) n).value());
        }

        public Optional<Boolean> getBoolean(String key) {
            return getOptional(key).filter(n -> n instanceof TagByte).map(n -> ((TagByte) n).value() != 0);
        }

        public Optional<TagCompound> getCompound(String key) {
            return getOptional(key).filter(n -> n instanceof TagCompound).map(n -> (TagCompound) n);
        }

        public TagCompound with(String key, TagNode value) {
            Map<String, TagNode> copy = new LinkedHashMap<>(entries);
            copy.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return new TagCompound(copy);
        }

        public TagCompound without(String key) {
            if (!entries.containsKey(key)) return this;
            Map<String, TagNode> copy = new LinkedHashMap<>(entries);
            copy.remove(key);
            return new TagCompound(copy);
        }
    }

    record TagIntArray(int[] value) implements TagNode {
        public TagIntArray {
            value = value == null ? new int[0] : value.clone();
        }
        @Override public byte typeId() { return 11; }
        @Override public int[] value() { return value.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof TagIntArray other && Arrays.equals(value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "TagIntArray[value=" + Arrays.toString(value) + "]"; }
    }

    record TagLongArray(long[] value) implements TagNode {
        public TagLongArray {
            value = value == null ? new long[0] : value.clone();
        }
        @Override public byte typeId() { return 12; }
        @Override public long[] value() { return value.clone(); }
        @Override public boolean equals(Object o) {
            return o instanceof TagLongArray other && Arrays.equals(value, other.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() { return "TagLongArray[value=" + Arrays.toString(value) + "]"; }
    }
}
