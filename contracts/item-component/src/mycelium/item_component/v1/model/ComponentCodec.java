package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure codec for translating between typed Java domain objects and {@link TagNode} trees.
 */
public interface ComponentCodec<T> {
    TagNode encode(T value);

    T decode(TagNode node) throws CodecException;

    /**
     * Deeply freezes the value so no external mutation of the argument
     * or any returned reference can alter the snapshot's state.
     */
    default T freeze(T value) {
        return ValueSemantics.freeze(value);
    }

    /**
     * Validates that the value conforms to the codec's domain constraints.
     */
    default void validate(T value) throws CodecException {
    }

    /**
     * Value equality for domain values produced or consumed by this codec.
     * Composes correctly through nested arrays, collections, and records.
     */
    default boolean semanticEquals(T a, T b) {
        return ValueSemantics.deepEquals(a, b);
    }

    /**
     * Value hash code for domain values produced or consumed by this codec.
     */
    default int semanticHash(T value) {
        return ValueSemantics.deepHashCode(value);
    }

    ComponentCodec<Integer> INT = new ComponentCodec<>() {
        @Override
        public TagNode encode(Integer value) {
            return new TagNode.TagInt(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Integer decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagInt ti) {
                return ti.value();
            }
            if (node instanceof TagNode.TagByte tb) {
                return (int) tb.value();
            }
            if (node instanceof TagNode.TagShort ts) {
                return (int) ts.value();
            }
            throw new CodecException("Expected TagInt, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<String> STRING = new ComponentCodec<>() {
        @Override
        public TagNode encode(String value) {
            return new TagNode.TagString(Objects.requireNonNull(value, "value"));
        }

        @Override
        public String decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagString ts) {
                return ts.value();
            }
            throw new CodecException("Expected TagString, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<Boolean> BOOLEAN = new ComponentCodec<>() {
        @Override
        public TagNode encode(Boolean value) {
            return TagNode.TagByte.ofBoolean(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Boolean decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagByte tb) {
                return tb.value() != 0;
            }
            if (node instanceof TagNode.TagInt ti) {
                return ti.value() != 0;
            }
            if (node instanceof TagNode.TagShort ts) {
                return ts.value() != 0;
            }
            throw new CodecException("Expected TagByte or numeric boolean tag, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<Long> LONG = new ComponentCodec<>() {
        @Override
        public TagNode encode(Long value) {
            return new TagNode.TagLong(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Long decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagLong tl) {
                return tl.value();
            }
            if (node instanceof TagNode.TagInt ti) {
                return (long) ti.value();
            }
            throw new CodecException("Expected TagLong, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<Double> DOUBLE = new ComponentCodec<>() {
        @Override
        public TagNode encode(Double value) {
            return new TagNode.TagDouble(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Double decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagDouble td) {
                return td.value();
            }
            if (node instanceof TagNode.TagFloat tf) {
                return (double) tf.value();
            }
            throw new CodecException("Expected TagDouble, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<Float> FLOAT = new ComponentCodec<>() {
        @Override
        public TagNode encode(Float value) {
            return new TagNode.TagFloat(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Float decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagFloat tf) {
                return tf.value();
            }
            if (node instanceof TagNode.TagDouble td) {
                double d = td.value();
                if (!Double.isInfinite(d) && (d > Float.MAX_VALUE || d < -Float.MAX_VALUE)) {
                    throw new CodecException("Double value " + d + " exceeds finite float range");
                }
                return (float) d;
            }
            throw new CodecException("Expected TagFloat, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<byte[]> BYTE_ARRAY = new ComponentCodec<>() {
        @Override
        public TagNode encode(byte[] value) {
            return new TagNode.TagByteArray(Objects.requireNonNull(value, "value"));
        }

        @Override
        public byte[] decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagByteArray tba) {
                return tba.value();
            }
            throw new CodecException("Expected TagByteArray, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<int[]> INT_ARRAY = new ComponentCodec<>() {
        @Override
        public TagNode encode(int[] value) {
            return new TagNode.TagIntArray(Objects.requireNonNull(value, "value"));
        }

        @Override
        public int[] decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagIntArray tia) {
                return tia.value();
            }
            throw new CodecException("Expected TagIntArray, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<long[]> LONG_ARRAY = new ComponentCodec<>() {
        @Override
        public TagNode encode(long[] value) {
            return new TagNode.TagLongArray(Objects.requireNonNull(value, "value"));
        }

        @Override
        public long[] decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagLongArray tla) {
                return tla.value();
            }
            throw new CodecException("Expected TagLongArray, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<TagNode.TagCompound> COMPOUND = new ComponentCodec<>() {
        @Override
        public TagNode encode(TagNode.TagCompound value) {
            return Objects.requireNonNull(value, "value");
        }

        @Override
        public TagNode.TagCompound decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagCompound tc) {
                return tc;
            }
            throw new CodecException("Expected TagCompound, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    ComponentCodec<Unit> UNIT = new ComponentCodec<>() {
        @Override
        public TagNode encode(Unit value) {
            Objects.requireNonNull(value, "value");
            return TagNode.TagCompound.empty();
        }

        @Override
        public Unit decode(TagNode node) throws CodecException {
            if (node instanceof TagNode.TagCompound tc && tc.isEmpty()) {
                return Unit.INSTANCE;
            }
            if (node instanceof TagNode.TagByte tb && tb.value() == 1) {
                return Unit.INSTANCE;
            }
            throw new CodecException("Expected empty TagCompound or boolean TagByte 1 for UNIT, got "
                    + (node == null ? "null" : node.getClass().getSimpleName()));
        }
    };

    static <E> ComponentCodec<List<E>> listOf(ComponentCodec<E> elementCodec) {
        Objects.requireNonNull(elementCodec, "elementCodec");
        return new ComponentCodec<>() {
            @Override
            public TagNode encode(List<E> list) {
                Objects.requireNonNull(list, "list");
                List<TagNode> nodes = new ArrayList<>(list.size());
                for (E item : list) {
                    nodes.add(elementCodec.encode(item));
                }
                return LogicalNbtBridge.toPhysicalList(nodes);
            }

            @Override
            public List<E> decode(TagNode node) throws CodecException {
                if (node instanceof TagNode.TagList listNode) {
                    List<TagNode> logicalNodes = LogicalNbtBridge.toLogicalList(listNode);
                    List<E> result = new ArrayList<>(logicalNodes.size());
                    for (TagNode elemNode : logicalNodes) {
                        result.add(elementCodec.decode(elemNode));
                    }
                    return Collections.unmodifiableList(result);
                }
                throw new CodecException("Expected TagList, got " + (node == null ? "null" : node.getClass().getSimpleName()));
            }

            @Override
            public List<E> freeze(List<E> list) {
                if (list == null) return null;
                List<E> frozen = new ArrayList<>(list.size());
                for (E item : list) {
                    frozen.add(elementCodec.freeze(item));
                }
                return Collections.unmodifiableList(frozen);
            }

            @Override
            public boolean semanticEquals(List<E> a, List<E> b) {
                if (a == b) return true;
                if (a == null || b == null) return false;
                if (a.size() != b.size()) return false;
                for (int i = 0; i < a.size(); i++) {
                    if (!elementCodec.semanticEquals(a.get(i), b.get(i))) return false;
                }
                return true;
            }

            @Override
            public int semanticHash(List<E> list) {
                if (list == null) return 0;
                int h = 1;
                for (E item : list) {
                    h = 31 * h + elementCodec.semanticHash(item);
                }
                return h;
            }
        };
    }

    static <V> ComponentCodec<Map<String, V>> mapOf(ComponentCodec<V> valueCodec) {
        Objects.requireNonNull(valueCodec, "valueCodec");
        return new ComponentCodec<>() {
            @Override
            public TagNode encode(Map<String, V> map) {
                Objects.requireNonNull(map, "map");
                Map<String, TagNode> entries = new TreeMap<>();
                for (Map.Entry<String, V> entry : map.entrySet()) {
                    entries.put(entry.getKey(), valueCodec.encode(entry.getValue()));
                }
                return new TagNode.TagCompound(entries);
            }

            @Override
            public Map<String, V> decode(TagNode node) throws CodecException {
                if (node instanceof TagNode.TagCompound compound) {
                    Map<String, V> result = new TreeMap<>();
                    for (Map.Entry<String, TagNode> entry : compound.entries().entrySet()) {
                        result.put(entry.getKey(), valueCodec.decode(entry.getValue()));
                    }
                    return Collections.unmodifiableMap(result);
                }
                throw new CodecException("Expected TagCompound for map, got " + (node == null ? "null" : node.getClass().getSimpleName()));
            }

            @Override
            public Map<String, V> freeze(Map<String, V> map) {
                if (map == null) return null;
                Map<String, V> frozen = new TreeMap<>();
                for (Map.Entry<String, V> entry : map.entrySet()) {
                    frozen.put(entry.getKey(), valueCodec.freeze(entry.getValue()));
                }
                return Collections.unmodifiableMap(frozen);
            }

            @Override
            public boolean semanticEquals(Map<String, V> a, Map<String, V> b) {
                if (a == b) return true;
                if (a == null || b == null) return false;
                if (a.size() != b.size()) return false;
                for (Map.Entry<String, V> entry : a.entrySet()) {
                    if (!b.containsKey(entry.getKey())) return false;
                    if (!valueCodec.semanticEquals(entry.getValue(), b.get(entry.getKey()))) return false;
                }
                return true;
            }

            @Override
            public int semanticHash(Map<String, V> map) {
                if (map == null) return 0;
                int h = 0;
                for (Map.Entry<String, V> entry : map.entrySet()) {
                    h += Objects.hashCode(entry.getKey()) ^ valueCodec.semanticHash(entry.getValue());
                }
                return h;
            }
        };
    }
}
