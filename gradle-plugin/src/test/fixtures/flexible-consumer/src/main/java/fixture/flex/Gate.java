package fixture.flex;

import fixture.flex.compat.OtherHome;

/** The one place that reaches into the integration. Not declared: the plugin finds it. */
final class Gate {
    static int value() {
        return OtherHome.present() ? 1 : 0;
    }
}
