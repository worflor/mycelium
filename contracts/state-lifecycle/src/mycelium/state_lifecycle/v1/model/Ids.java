package mycelium.state_lifecycle.v1.model;

/** The one syntax of a namespaced id: {@code namespace:path}, lowercase, so an id is a token and never prose. */
final class Ids {
    private Ids() {
    }

    static String check(String what, String value) {
        if (value == null) {
            throw new IllegalArgumentException(what + " is null");
        }
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1 || value.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException(what + " '" + value + "' is not namespace:path");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '/' || c == ':';
            if (!ok) {
                throw new IllegalArgumentException(what + " '" + value + "' may use only a-z, 0-9, _ - . / and one ':'");
            }
        }
        return value;
    }

    static String namespace(String value) {
        return value.substring(0, value.indexOf(':'));
    }
}
