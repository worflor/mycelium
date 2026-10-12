package mycelium.item_presentation.v1.model;

import mycelium.item_component.v1.model.ComponentDefinition;
import mycelium.item_component.v1.model.ComponentId;
import mycelium.item_component.v1.model.ComponentRegistry;
import mycelium.item_component.v1.model.RecordCodec;
import mycelium.item_component.v1.model.Unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The components a program may read, as presentation sees them: whether each is network-visible (the client can see
 * what it holds), whether it is a unit component, and the kind of each field. A consumer states it once for its
 * component registry; compiling a program against it is how a program that reads server-private state is refused
 * (law S5).
 *
 * <p>The catalog is a file the build reads, since a build cannot load a consumer's registry; {@link #disagreements} holds
 * it to the registry's definitions (`item.component@1`), in a test of the consumer's.
 */
public final class ComponentCatalog {
    /** One component. A unit component has no fields; a record component lists its leaf field paths. */
    public record Component(String id, boolean networkSynced, boolean unit, Map<String, FieldKind> fields) {
        public Component {
            if (!Ids.resource(id)) {
                throw new IllegalArgumentException("'" + id + "' is not a component id (namespace:path)");
            }
            if (unit && !fields.isEmpty()) {
                throw new IllegalArgumentException("the unit component " + id + " has no fields");
            }
            for (String path : fields.keySet()) {
                if (!Ids.field(path)) {
                    throw new IllegalArgumentException("'" + path + "' is not a field path");
                }
            }
            fields = Collections.unmodifiableMap(new TreeMap<>(fields));
        }

        /** A unit component: present or not. */
        public static Component unit(String id, boolean networkSynced) {
            return new Component(id, networkSynced, true, Collections.<String, FieldKind>emptyMap());
        }

        /** A record component with these leaf fields. */
        public static Component record(String id, boolean networkSynced, Map<String, FieldKind> fields) {
            return new Component(id, networkSynced, false, fields);
        }
    }

    private final Map<String, Component> components;

    private ComponentCatalog(Map<String, Component> components) {
        this.components = Collections.unmodifiableMap(components);
    }

    public static ComponentCatalog of(List<Component> components) {
        Map<String, Component> map = new TreeMap<>();
        for (Component c : components) {
            if (map.put(c.id(), c) != null) {
                throw new IllegalArgumentException("the component " + c.id() + " is listed twice");
            }
        }
        return new ComponentCatalog(map);
    }

    /** The component, or {@code null} when the catalog does not have it. */
    public Component component(String id) {
        return components.get(id);
    }

    public List<Component> all() {
        return Collections.unmodifiableList(new ArrayList<>(components.values()));
    }

    /**
     * Where this catalog says something the registry's definitions do not: a component the registry lacks, a different
     * network visibility, a unit component that is a record or the reverse, a field the record's codec does not have.
     * The build reads the catalog as a file; a consumer calls this in a test of its own, so that file cannot drift from the
     * definitions it describes (a catalog that calls a server-private component visible would defeat law S5).
     */
    public List<Diagnostic> disagreements(ComponentRegistry registry) {
        List<Diagnostic> out = new ArrayList<>();
        for (Component c : components.values()) {
            String at = "components." + c.id();
            Optional<ComponentDefinition<?>> found = registry.get(ComponentId.of(c.id()));
            if (!found.isPresent()) {
                out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, "the registry has no such component"));
                continue;
            }
            ComponentDefinition<?> definition = found.get();
            if (definition.networkSynced() != c.networkSynced()) {
                out.add(new Diagnostic(c.networkSynced() ? Diagnostic.Kind.SERVER_PRIVATE : Diagnostic.Kind.CATALOG, at,
                        "the catalog says networkSynced=" + c.networkSynced() + " and the definition says " + definition.networkSynced()));
            }
            boolean unit = definition.defaultValue() instanceof Unit;
            if (unit != c.unit()) {
                out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at, "the catalog says unit=" + c.unit() + " and the definition's default "
                        + (unit ? "is" : "is not") + " the unit value"));
            }
            if (!unit) {
                Set<String> names = definition.codec() instanceof RecordCodec<?> rc ? rc.fieldNames() : Collections.<String>emptySet();
                for (String path : c.fields().keySet()) {
                    String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                    if (!names.contains(head)) {
                        out.add(new Diagnostic(Diagnostic.Kind.CATALOG, at + ".fields." + path,
                                "the definition's record codec has no field '" + head + "'"));
                    }
                }
            }
        }
        return out;
    }

    /** The catalog as canonical JSON text. */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", Long.valueOf(1));
        Map<String, Object> cs = new LinkedHashMap<>();
        for (Component c : components.values()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("networkSynced", Boolean.valueOf(c.networkSynced()));
            if (c.unit()) {
                one.put("unit", Boolean.TRUE);
            } else {
                Map<String, Object> fields = new LinkedHashMap<>();
                for (Map.Entry<String, FieldKind> f : c.fields().entrySet()) {
                    fields.put(f.getKey(), f.getValue().id());
                }
                one.put("fields", fields);
            }
            cs.put(c.id(), one);
        }
        root.put("components", cs);
        return Json.write(root);
    }

    public static Result<ComponentCatalog> parse(String text) {
        Object tree;
        try {
            tree = Json.parse(text);
        } catch (Json.SyntaxException e) {
            return fail(Diagnostic.Kind.SYNTAX, "components", e.getMessage());
        }
        if (!(tree instanceof Map<?, ?> root) || !Long.valueOf(1).equals(root.get("schema"))
                || !(root.get("components") instanceof Map<?, ?> cs) || root.size() != 2) {
            return fail(Diagnostic.Kind.SCHEMA, "components", "the catalog is {\"schema\": 1, \"components\": {...}}");
        }
        List<Component> out = new ArrayList<>();
        for (Map.Entry<?, ?> e : cs.entrySet()) {
            String id = (String) e.getKey();
            String at = "components." + id;
            if (!(e.getValue() instanceof Map<?, ?> one) || !(one.get("networkSynced") instanceof Boolean)) {
                return fail(Diagnostic.Kind.SCHEMA, at, "a component states \"networkSynced\" (true or false), and may state \"unit\" or \"fields\"");
            }
            for (Object key : one.keySet()) {
                if (!"networkSynced".equals(key) && !"unit".equals(key) && !"fields".equals(key)) {
                    return fail(Diagnostic.Kind.SCHEMA, at, "unknown field \"" + key + "\"");
                }
            }
            boolean unit = Boolean.TRUE.equals(one.get("unit"));
            Map<String, FieldKind> fields = new LinkedHashMap<>();
            if (one.containsKey("fields")) {
                if (!(one.get("fields") instanceof Map<?, ?> fm)) {
                    return fail(Diagnostic.Kind.SCHEMA, at + ".fields", "\"fields\" maps a field path to bool, int or string");
                }
                for (Map.Entry<?, ?> f : fm.entrySet()) {
                    try {
                        fields.put((String) f.getKey(), FieldKind.parse(String.valueOf(f.getValue())));
                    } catch (IllegalArgumentException bad) {
                        return fail(Diagnostic.Kind.SCHEMA, at + ".fields." + f.getKey(), bad.getMessage());
                    }
                }
            }
            try {
                out.add(new Component(id, (Boolean) one.get("networkSynced"), unit, fields));
            } catch (IllegalArgumentException bad) {
                return fail(Diagnostic.Kind.SCHEMA, at, bad.getMessage());
            }
        }
        return new Result.Ok<>(of(out));
    }

    private static Result<ComponentCatalog> fail(Diagnostic.Kind kind, String where, String message) {
        return new Result.Failed<>(Collections.singletonList(new Diagnostic(kind, where, message)));
    }
}
