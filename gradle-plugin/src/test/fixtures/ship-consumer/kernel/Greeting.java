package fixture.ship.kernel;

import java.util.List;

/**
 * The fixture's whole pure layer: one Java 9+ call ({@code List.of}, static factory methods added in JDK 9) that does
 * not exist on Java 8 until JvmDowngrader backports it. Written for release 21 (`build.gradle.kts`); shipped for
 * Minecraft 1.12.2's era, Java 8.
 */
public final class Greeting {
    private Greeting() {
    }

    public static String hello() {
        return String.join(",", List.of("hello", "java8"));
    }

    /** Run directly against the shipped, downgraded classes on a Java 8 JVM: the end-to-end proof for 0006. */
    public static void main(String[] args) {
        String got = hello();
        if (!"hello,java8".equals(got)) {
            throw new AssertionError("expected 'hello,java8', got '" + got + "'");
        }
        System.out.println("Mycelium ship-consumer fixture: OK (" + got + ")");
    }
}
