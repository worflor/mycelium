package mycelium.item_presentation.v1.model;

import java.util.regex.Pattern;

/** The identifier shapes this contract accepts. A model reference is a resource location: {@code namespace:path}. */
public final class Ids {
    private static final Pattern RESOURCE = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
    private static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
    private static final Pattern CHOICE = Pattern.compile("[a-z0-9_]+");

    private Ids() {
    }

    /** A resource location: an item id, a component id or a model reference. */
    public static boolean resource(String s) {
        return s != null && RESOURCE.matcher(s).matches();
    }

    /** An input's name. */
    public static boolean name(String s) {
        return s != null && NAME.matcher(s).matches();
    }

    /** A path to a field inside a component's record: names joined by dots. */
    public static boolean field(String s) {
        return s != null && FIELD.matcher(s).matches();
    }

    /** One value of an enum domain, which a model template may put into a model reference. */
    public static boolean choice(String s) {
        return s != null && CHOICE.matcher(s).matches();
    }

    public static String namespace(String resource) {
        return resource.substring(0, resource.indexOf(':'));
    }

    public static String path(String resource) {
        return resource.substring(resource.indexOf(':') + 1);
    }
}
