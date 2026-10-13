package mycelium.item_component.v1.model;

import java.util.*;
import java.util.function.Function;

/**
 * Codec for a small record: named fields, each with its own {@link ComponentCodec}, some of them optional.
 *
 * <p>It is a {@link ComponentCodec} and a {@link FieldAwareCodec} at once, so a component bound with
 * {@link #definition(ComponentId, Object)} keeps fields it does not know across decode, edit and encode (law T9), and a
 * plain use as a codec (a field of another record, a list element) simply ignores them.
 *
 * <ul>
 *   <li>A required field that is missing, or any field of the wrong shape, is a {@link CodecException}.</li>
 *   <li>An optional field that is missing decodes as {@link Optional#empty()}, and an absent one is never written.</li>
 *   <li>Fields are written in sorted key order, remainder included (law T6).</li>
 * </ul>
 *
 * <pre>{@code
 * record Charm(String owner, int level, Optional<String> memory) {}
 *
 * Field<Charm, String> OWNER = RecordCodec.field("owner", ComponentCodec.STRING, Charm::owner);
 * Field<Charm, Integer> LEVEL = RecordCodec.field("level", ComponentCodec.INT, Charm::level);
 * Field<Charm, Optional<String>> MEMORY = RecordCodec.optional("memory", ComponentCodec.STRING, Charm::memory);
 *
 * RecordCodec<Charm> CODEC = RecordCodec.of(
 *     r -> new Charm(r.get(OWNER), r.get(LEVEL), r.get(MEMORY)), OWNER, LEVEL, MEMORY);
 * }</pre>
 */
public final class RecordCodec<T> implements ComponentCodec<T>, FieldAwareCodec<T> {

    /** One named field of a record {@code T}, read as {@code V} (the field's value, or an {@link Optional} of it). */
    public static final class Field<T, V> {
        private final String name;
        private final boolean optional;
        private final Function<T, V> getter;
        private final ComponentCodec<Object> codec;

        @SuppressWarnings("unchecked")
        private Field(String name, boolean optional, ComponentCodec<?> codec, Function<T, V> getter) {
            this.name = Objects.requireNonNull(name, "name");
            this.optional = optional;
            this.codec = (ComponentCodec<Object>) Objects.requireNonNull(codec, "codec");
            this.getter = Objects.requireNonNull(getter, "getter");
        }

        public String name() {
            return name;
        }

        public boolean isOptional() {
            return optional;
        }

        /** The inner value of the field of {@code record}, or null when it is an absent optional. */
        private Object inner(T record) {
            V v = getter.apply(record);
            if (optional) {
                Optional<?> o = (Optional<?>) Objects.requireNonNull(v, "optional field '" + name + "' must not be null");
                return o.isPresent() ? o.get() : null;
            }
            return Objects.requireNonNull(v, "field '" + name + "' must not be null");
        }

        private V wrap(Object inner) {
            @SuppressWarnings("unchecked")
            V v = (V) (optional ? Optional.ofNullable(inner) : inner);
            return v;
        }
    }

    /** What a record's constructor reads its field values from. */
    public interface Reader {
        <V> V get(Field<?, V> field);
    }

    /** Builds a record from its decoded fields. */
    public interface Maker<T> {
        T make(Reader fields);
    }

    /** A required field. */
    public static <T, F> Field<T, F> field(String name, ComponentCodec<F> codec, Function<T, F> getter) {
        return new Field<>(name, false, codec, getter);
    }

    /** An optional field: absent when empty, and then not written. */
    public static <T, F> Field<T, Optional<F>> optional(String name, ComponentCodec<F> codec,
                                                         Function<T, Optional<F>> getter) {
        return new Field<>(name, true, codec, getter);
    }

    @SafeVarargs
    public static <T> RecordCodec<T> of(Maker<T> maker, Field<T, ?>... fields) {
        return new RecordCodec<>(maker, Arrays.asList(fields));
    }

    private final Maker<T> maker;
    private final List<Field<T, ?>> fields;
    private final Set<String> names;

    private RecordCodec(Maker<T> maker, List<Field<T, ?>> fields) {
        this.maker = Objects.requireNonNull(maker, "maker");
        List<Field<T, ?>> copy = new ArrayList<>(fields);
        Set<String> seen = new TreeSet<>();
        for (Field<T, ?> f : copy) {
            Objects.requireNonNull(f, "field");
            if (!seen.add(f.name)) {
                throw new IllegalArgumentException("Duplicate record field '" + f.name + "'");
            }
        }
        this.fields = Collections.unmodifiableList(copy);
        this.names = Collections.unmodifiableSet(seen);
    }

    /** The names this codec reads and writes; any other key of the compound is remainder. */
    public Set<String> fieldNames() {
        return names;
    }

    /** A definition of the component {@code id} with this codec as both its codec and its field-aware lens. */
    public ComponentDefinition<T> definition(ComponentId id, T defaultValue) {
        return new ComponentDefinition<>(id, this, defaultValue).withFieldAwareCodec(this);
    }

    /** A definition of the component {@code id} with no default (see {@link ComponentDefinition#withoutDefault}). */
    public ComponentDefinition<T> definition(ComponentId id) {
        return definition(id, null);
    }

    // ---- ComponentCodec

    @Override
    public TagNode encode(T value) {
        return new TagNode.TagCompound(encodeFields(Objects.requireNonNull(value, "value")));
    }

    @Override
    public T decode(TagNode node) throws CodecException {
        if (!(node instanceof TagNode.TagCompound)) {
            throw new CodecException("Expected TagCompound for record, got " + (node == null ? "null" : node.getClass().getSimpleName()));
        }
        return decodeCompound((TagNode.TagCompound) node);
    }

    @Override
    public void validate(T value) throws CodecException {
        for (Field<T, ?> f : fields) {
            Object inner = f.inner(value);
            if (inner != null) {
                f.codec.validate(inner);
            }
        }
    }

    @Override
    public T freeze(T value) {
        if (value == null) return null;
        Map<Field<?, ?>, Object> frozen = new IdentityHashMap<>();
        for (Field<T, ?> f : fields) {
            Object inner = f.inner(value);
            frozen.put(f, inner == null ? null : f.codec.freeze(inner));
        }
        return maker.make(readerOf(frozen));
    }

    @Override
    public boolean semanticEquals(T a, T b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        for (Field<T, ?> f : fields) {
            Object x = f.inner(a);
            Object y = f.inner(b);
            if (x == null || y == null) {
                if (x != y) return false;
            } else if (!f.codec.semanticEquals(x, y)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int semanticHash(T value) {
        if (value == null) return 0;
        int h = 1;
        for (Field<T, ?> f : fields) {
            Object inner = f.inner(value);
            h = 31 * h + (inner == null ? 0 : f.codec.semanticHash(inner));
        }
        return h;
    }

    // ---- FieldAwareCodec

    @Override
    public DecodedResult<T> decodeFieldAware(TagNode.TagCompound raw) throws CodecException {
        Objects.requireNonNull(raw, "raw");
        T value = decodeCompound(raw);
        Map<String, TagNode> remainder = new TreeMap<>();
        Set<String> footprint = new TreeSet<>();
        for (Map.Entry<String, TagNode> e : raw.entries().entrySet()) {
            if (names.contains(e.getKey())) {
                footprint.add(e.getKey());
            } else {
                remainder.put(e.getKey(), e.getValue());
            }
        }
        return new DecodedResult<>(value, new TagNode.TagCompound(remainder), footprint);
    }

    @Override
    public TagNode.TagCompound updateFieldAware(TagNode.TagCompound originalRaw, T newValue) throws CodecException {
        Objects.requireNonNull(originalRaw, "originalRaw");
        Map<String, TagNode> merged = new TreeMap<>();
        for (Map.Entry<String, TagNode> e : originalRaw.entries().entrySet()) {
            if (!names.contains(e.getKey())) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        merged.putAll(encodeFields(Objects.requireNonNull(newValue, "newValue")));
        return new TagNode.TagCompound(merged);
    }

    // ---- internals

    private Map<String, TagNode> encodeFields(T value) {
        Map<String, TagNode> out = new TreeMap<>();
        for (Field<T, ?> f : fields) {
            Object inner = f.inner(value);
            if (inner != null) {
                out.put(f.name, f.codec.encode(inner));
            }
        }
        return out;
    }

    private T decodeCompound(TagNode.TagCompound raw) throws CodecException {
        Map<Field<?, ?>, Object> decoded = new IdentityHashMap<>();
        for (Field<T, ?> f : fields) {
            TagNode node = raw.get(f.name);
            if (node == null) {
                if (!f.optional) {
                    throw new CodecException("Missing required record field '" + f.name + "'");
                }
                decoded.put(f, null);
                continue;
            }
            Object inner;
            try {
                inner = f.codec.decode(node);
            } catch (CodecException e) {
                throw new CodecException("Record field '" + f.name + "': " + e.getMessage(), e);
            }
            if (inner == null) {
                throw new CodecException("Record field '" + f.name + "' decoded to null");
            }
            decoded.put(f, inner);
        }
        T made;
        try {
            made = maker.make(readerOf(decoded));
        } catch (RuntimeException e) {
            throw new CodecException("Record rejected its fields: " + e.getMessage(), e);
        }
        if (made == null) {
            throw new CodecException("Record maker returned null");
        }
        return made;
    }

    private static Reader readerOf(final Map<Field<?, ?>, Object> inner) {
        return new Reader() {
            @Override
            @SuppressWarnings("unchecked")
            public <V> V get(Field<?, V> field) {
                if (!inner.containsKey(field)) {
                    throw new IllegalArgumentException("Field '" + field.name + "' is not part of this record codec");
                }
                return ((Field<Object, V>) field).wrap(inner.get(field));
            }
        };
    }
}
