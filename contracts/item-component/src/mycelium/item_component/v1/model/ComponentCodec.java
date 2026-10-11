package mycelium.item_component.v1.model;

import java.util.*;

/**
 * Pure codec for translating between typed Java domain objects and {@link TagNode} trees.
 */
public interface ComponentCodec<T> {
    TagNode encode(T value);

    T decode(TagNode node) throws CodecException;

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
                return new TagNode.TagList(nodes);
            }

            @Override
            public List<E> decode(TagNode node) throws CodecException {
                if (node instanceof TagNode.TagList listNode) {
                    List<E> result = new ArrayList<>(listNode.size());
                    for (int i = 0; i < listNode.size(); i++) {
                        result.add(elementCodec.decode(listNode.get(i)));
                    }
                    return Collections.unmodifiableList(result);
                }
                throw new CodecException("Expected TagList, got " + (node == null ? "null" : node.getClass().getSimpleName()));
            }
        };
    }
}
