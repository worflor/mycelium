package fixture.flex.compat;

import com.example.other.Api;

/** The integration's home package: the only place that may name the other mod's API. */
public final class OtherHome {
    public static boolean present() {
        return Api.VERSION > 0;
    }
}
